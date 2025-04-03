
package com.RealState.servlets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

@MultipartConfig(
    fileSizeThreshold = 1024 * 1024,     // 1 MB
    maxFileSize = 1024 * 1024 * 10,      // 10 MB
    maxRequestSize = 1024 * 1024 * 15    // 15 MB
)
public class UserSettingServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(UserSettingServlet.class.getName());
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Use relative paths that will be resolved at runtime
    private static final String USER_DATA_DIR = "C:\\Users\\user\\Downloads\\project\\RealState\\src\\main\\webapp\\WEB-INF";
    private static final String USER_SETTINGS_FILENAME = "C:\\Users\\user\\Downloads\\project\\RealState\\src\\main\\webapp\\WEB-INF\\data\\userSettings.json";
    private static final String AVATAR_UPLOAD_DIR_PATH = "assets/avatars";

    // For thread safety during file operations
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private String userJsonPath;
    private String avatarUploadDir;
    private String currentTimestamp;
    private String currentUser;

    @Override
    public void init() throws ServletException {
        super.init();

        // Resolve the real paths using the ServletContext
        ServletContext context = getServletContext();
        String realPath = context.getRealPath("/");
        System.out.println(realPath);

        // Create the data directory path
        File dataDir = new File(USER_DATA_DIR);
        if (!dataDir.exists()) {
            boolean created = dataDir.mkdirs();
            if (!created) {
                logger.warning("Failed to create data directory: " + dataDir.getAbsolutePath());
            }
        }

        // Set the full path to the user settings JSON file
        userJsonPath = USER_SETTINGS_FILENAME;

        // Create the avatar upload directory
        avatarUploadDir = realPath + AVATAR_UPLOAD_DIR_PATH;
        File uploadDir = new File(avatarUploadDir);
        if (!uploadDir.exists()) {
            boolean created = uploadDir.mkdirs();
            if (!created) {
                logger.warning("Failed to create avatar upload directory: " + uploadDir.getAbsolutePath());
            }
        }

        // Set default values
        currentTimestamp = getCurrentDateTime();
        currentUser = "IT24102083";  // Default user

        logger.info("User settings path: " + userJsonPath);
        logger.info("Avatar upload directory: " + avatarUploadDir);
        logger.info("Servlet initialized at " + currentTimestamp + " by " + currentUser);
    }



    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();
        
        try {
            String action = request.getParameter("action");
            String username = request.getParameter("username");
            
            // If username is not provided, use the current user
            if (username == null || username.isEmpty()) {
                username = currentUser;
                logger.info("Using default username: " + username);
            }
            
            if (action == null || action.isEmpty()) {
                throw new IllegalArgumentException("Action is a required parameter");
            }
            
            // Log the incoming request
            logger.info("Processing " + action + " for user: " + username);
            currentTimestamp = getCurrentDateTime();
            
            // Read the current user data with read lock
            JsonArray users;
            rwLock.readLock().lock();
            try {
                users = readUserData();
                // Log the current data for debugging
                System.out.println("Current user data: " + gson.toJson(users));
            } finally {
                rwLock.readLock().unlock();
            }
            
            boolean userFound = false;
            JsonObject user = null;
            int userIndex = -1;
            
            // Find the user
            for (int i = 0; i < users.size(); i++) {
                JsonObject currentUser = users.get(i).getAsJsonObject();
                
                if (currentUser.has("username") && 
                    currentUser.get("username").getAsString().equals(username)) {
                    userFound = true;
                    user = currentUser;
                    userIndex = i;
                    break;
                }
            }
            
            // Create new user if not found
            if (!userFound) {
                user = new JsonObject();
                user.addProperty("username", username);
                user.addProperty("createdAt", currentTimestamp);
                users.add(user);
                userIndex = users.size() - 1;
                logger.info("Created new user: " + username);
                System.out.println("Created new user: " + username);
            }
            
            // Record the update timestamp
            user.addProperty("lastUpdated", currentTimestamp);
            
            // Process the action
            switch (action) {
                case "saveProfile":
                    updateProfile(user, request);
                    break;
                case "savePassword":
                    if (!userFound) {
                        // For new users, don't require current password
                        String newPassword = request.getParameter("newPassword");
                        if (newPassword != null && !newPassword.isEmpty()) {
                            user.addProperty("password", newPassword);
                        } else {
                            throw new IllegalArgumentException("Password is required for new users");
                        }
                    } else {
                        updatePassword(user, request);
                    }
                    break;
                case "saveNotifications":
                    updateNotifications(user, request);
                    break;
                case "saveAppearance":
                    updateAppearance(user, request);
                    break;
                case "saveListings":
                    updateListings(user, request);
                    break;
                case "uploadAvatar":
                    String avatarPath = handleAvatarUpload(request, username);
                    user.addProperty("avatarPath", avatarPath);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid action: " + action);
            }
            
            // Update the user in the array if it was modified
            if (userIndex >= 0 && userIndex < users.size()) {
                users.set(userIndex, user);
            }
            
            // Write updated data back to file with write lock
            rwLock.writeLock().lock();
            try {
                writeUserData(users);
                // Log the updated data
                System.out.println("Updated user data: " + gson.toJson(users));
            } finally {
                rwLock.writeLock().unlock();
            }
            
            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "Settings updated successfully");
            jsonResponse.addProperty("timestamp", currentTimestamp);
            jsonResponse.addProperty("user", username);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing settings", e);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error: " + e.getMessage());
            jsonResponse.addProperty("timestamp", currentTimestamp);
            System.err.println("Error processing settings: " + e.getMessage());
        }
        
        String responseJson = gson.toJson(jsonResponse);
        System.out.println("Response: " + responseJson);
        out.print(responseJson);
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();
        
        try {
            String username = request.getParameter("username");
            
            // If username is not provided, use the current user
            if (username == null || username.isEmpty()) {
                username = currentUser;
                logger.info("Using default username for GET request: " + username);
            }
            
            currentTimestamp = getCurrentDateTime();
            
            // Read user data with read lock
            JsonArray users;
            rwLock.readLock().lock();
            try {
                users = readUserData();
                System.out.println("Reading user data for: " + username);
                System.out.println("Current data: " + gson.toJson(users));
            } finally {
                rwLock.readLock().unlock();
            }
            
            boolean userFound = false;
            
            // Find the user and return their settings
            for (JsonElement elem : users) {
                JsonObject user = elem.getAsJsonObject();
                
                if (user.has("username") && 
                    user.get("username").getAsString().equals(username)) {
                    userFound = true;
                    jsonResponse.addProperty("success", true);
                    jsonResponse.add("user", user);
                    System.out.println("Found user: " + username);
                    break;
                }
            }
            
            if (!userFound) {
                // If user not found, return a new user object with default values
                JsonObject newUser = new JsonObject();
                newUser.addProperty("username", username);
                newUser.addProperty("createdAt", currentTimestamp);
                newUser.addProperty("firstName", "");
                newUser.addProperty("lastName", "");
                newUser.addProperty("email", "");
                
                JsonObject appearance = new JsonObject();
                appearance.addProperty("theme", "default");
                appearance.addProperty("language", "en");
                newUser.add("appearance", appearance);
                
                jsonResponse.addProperty("success", true);
                jsonResponse.add("user", newUser);
                jsonResponse.addProperty("isNew", true);
                
                logger.info("Returning new user object for: " + username);
                System.out.println("Creating new user object for: " + username);
            }
            
            jsonResponse.addProperty("timestamp", currentTimestamp);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error retrieving settings", e);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error: " + e.getMessage());
            jsonResponse.addProperty("timestamp", currentTimestamp);
            System.err.println("Error retrieving settings: " + e.getMessage());
        }
        
        String responseJson = gson.toJson(jsonResponse);
        System.out.println("Response: " + responseJson);
        out.print(responseJson);
    }
    
    private JsonArray readUserData() throws IOException {
        File jsonFile = new File(userJsonPath);
        
        // If file doesn't exist or is empty, return empty array
        if (!jsonFile.exists() || jsonFile.length() == 0) {
            // Create the file with an empty array if it doesn't exist
            if (!jsonFile.exists()) {
                if (jsonFile.getParentFile() != null && !jsonFile.getParentFile().exists()) {
                    jsonFile.getParentFile().mkdirs();
                }
                
                try (FileWriter writer = new FileWriter(jsonFile)) {
                    writer.write("[]");
                    System.out.println("Created new user settings file with empty array");
                }
            }
            return new JsonArray();
        }
        
        // Read the file content in one go, avoiding the reset issue
        String jsonContent;
        try {
            jsonContent = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
            System.out.println("Read user settings file: " + jsonContent);
        } catch (IOException e) {
            logger.severe("Failed to read user settings file: " + e.getMessage());
            System.err.println("Failed to read user settings file: " + e.getMessage());
            return new JsonArray();
        }
        
        // Parse the JSON
        try {
            JsonElement element = gson.fromJson(jsonContent, JsonElement.class);
            if (element == null || !element.isJsonArray()) {
                logger.warning("User settings file contains invalid JSON. Creating backup and returning empty array.");
                System.err.println("User settings file contains invalid JSON. Creating backup and returning empty array.");
                // Create backup of corrupted file
                backupCorruptedFile(jsonFile);
                return new JsonArray();
            }
            return element.getAsJsonArray();
        } catch (JsonParseException e) {
            logger.severe("Error parsing JSON file: " + e.getMessage());
            System.err.println("Error parsing JSON file: " + e.getMessage());
            // Create backup of corrupted file
            backupCorruptedFile(jsonFile);
            return new JsonArray();
        }
    }
    
    private void backupCorruptedFile(File jsonFile) {
        try {
            File backupFile = new File(jsonFile.getParent(), 
                "userSettings_corrupted_" + System.currentTimeMillis() + ".json");
            Files.copy(jsonFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Created backup of corrupted file: " + backupFile.getAbsolutePath());
            System.out.println("Created backup of corrupted file: " + backupFile.getAbsolutePath());
            
            // Reset the original file to an empty array
            try (FileWriter writer = new FileWriter(jsonFile)) {
                writer.write("[]");
            }
        } catch (IOException e) {
            logger.severe("Failed to backup corrupted file: " + e.getMessage());
            System.err.println("Failed to backup corrupted file: " + e.getMessage());
        }
    }
    
    private void writeUserData(JsonArray users) throws IOException {
        File jsonFile = new File(userJsonPath);
        
        // Create parent directory if it doesn't exist
        if (!jsonFile.getParentFile().exists()) {
            boolean created = jsonFile.getParentFile().mkdirs();
            if (!created) {
                logger.warning("Failed to create parent directory for user settings file");
                System.err.println("Failed to create parent directory for user settings file");
            }
        }
        
        // Create backup before writing
        if (jsonFile.exists() && jsonFile.length() > 0) {
            try {
                File backupFile = new File(jsonFile.getParent(), "userSettings_backup.json");
                Files.copy(jsonFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Created backup before writing");
            } catch (IOException e) {
                logger.warning("Failed to create backup before writing: " + e.getMessage());
                System.err.println("Failed to create backup before writing: " + e.getMessage());
                // Continue anyway
            }
        }
        
        // Write to a temporary file first
        File tempFile = new File(jsonFile.getParent(), "userSettings_temp.json");
        
        try (Writer writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(users, writer);
            writer.flush();
            System.out.println("Wrote data to temporary file: " + gson.toJson(users));
        }
        
        // If write was successful, move temp file to real file (safer atomic operation)
        try {
            Files.move(tempFile.toPath(), jsonFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Successfully saved user settings to " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            logger.severe("Failed to replace settings file with temporary file: " + e.getMessage());
            System.err.println("Failed to replace settings file with temporary file: " + e.getMessage());
            throw new IOException("Failed to save settings: " + e.getMessage(), e);
        }
    }
    
    // The rest of your methods (handleAvatarUpload, updateProfile, etc.) remain the same...
    
    private String handleAvatarUpload(HttpServletRequest request, String username) throws ServletException, IOException {
        // Create upload directory if it doesn't exist
        File uploadDir = new File(avatarUploadDir);
        if (!uploadDir.exists()) {
            boolean created = uploadDir.mkdirs();
            if (!created) {
                logger.warning("Failed to create avatar upload directory: " + avatarUploadDir);
                System.err.println("Failed to create avatar upload directory: " + avatarUploadDir);
            }
        }
        
        // Get the avatar file part
        Part filePart = request.getPart("avatar");
        if (filePart == null || filePart.getSize() == 0) {
            throw new IllegalArgumentException("No file uploaded or file is empty");
        }
        
        // Validate file type
        String contentType = filePart.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed");
        }
        
        // Generate a unique filename
        String fileName = username + "_" + UUID.randomUUID().toString() + getFileExtension(filePart);
        Path filePath = Paths.get(avatarUploadDir, fileName);
        
        // Save the file
        try (InputStream fileContent = filePart.getInputStream()) {
            Files.copy(fileContent, filePath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Avatar uploaded for user: " + username + " at path: " + filePath);
            System.out.println("Avatar uploaded for user: " + username + " at path: " + filePath);
        } catch (IOException e) {
            logger.severe("Failed to save avatar file: " + e.getMessage());
            System.err.println("Failed to save avatar file: " + e.getMessage());
            throw new IOException("Failed to save avatar file: " + e.getMessage(), e);
        }
        
        return "/" + AVATAR_UPLOAD_DIR_PATH + "/" + fileName; // Return web path, not file system path
    }
    
    private void updateProfile(JsonObject user, HttpServletRequest request) {
        updateStringProperty(user, "firstName", request);
        updateStringProperty(user, "lastName", request);
        updateStringProperty(user, "email", request);
        updateStringProperty(user, "phone", request);
        updateStringProperty(user, "bio", request);
        updateStringProperty(user, "jobTitle", request);
        updateStringProperty(user, "licenseNumber", request);
        updateStringProperty(user, "experience", request);
        updateStringProperty(user, "specialization", request);
        
        // If first name and last name are provided, update the full name
        if (request.getParameter("firstName") != null && request.getParameter("lastName") != null) {
            String fullName = request.getParameter("firstName") + " " + request.getParameter("lastName");
            user.addProperty("fullName", fullName);
        }
        
        System.out.println("Updated profile for user: " + user.get("username").getAsString());
    }
    
    private void updatePassword(JsonObject user, HttpServletRequest request) {
        String currentPassword = request.getParameter("currentPassword");
        String newPassword = request.getParameter("newPassword");
        
        if (currentPassword == null || newPassword == null) {
            throw new IllegalArgumentException("Current password and new password are required");
        }
        
        // Verify current password matches
        if (!user.has("password") || !user.get("password").getAsString().equals(currentPassword)) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        
        user.addProperty("password", newPassword);
        user.addProperty("passwordLastChanged", currentTimestamp);
        logger.info("Password changed for user: " + user.get("username").getAsString());
        System.out.println("Password changed for user: " + user.get("username").getAsString());
    }
    
    private void updateNotifications(JsonObject user, HttpServletRequest request) {
        JsonObject notifications;
        
        if (user.has("notifications")) {
            notifications = user.getAsJsonObject("notifications");
        } else {
            notifications = new JsonObject();
            user.add("notifications", notifications);
        }
        
        updateBooleanProperty(notifications, "emailInquiries", request);
        updateBooleanProperty(notifications, "emailPropertyUpdates", request);
        updateBooleanProperty(notifications, "emailNewsletter", request);
        updateBooleanProperty(notifications, "smsUrgentInquiries", request);
        updateBooleanProperty(notifications, "smsAppointments", request);
        
        // Add lastUpdated timestamp to the notifications object
        notifications.addProperty("lastUpdated", currentTimestamp);
        System.out.println("Updated notifications for user: " + user.get("username").getAsString());
    }
    
    private void updateAppearance(JsonObject user, HttpServletRequest request) {
        JsonObject appearance;
        
        if (user.has("appearance")) {
            appearance = user.getAsJsonObject("appearance");
        } else {
            appearance = new JsonObject();
            user.add("appearance", appearance);
        }
        
        updateStringProperty(appearance, "language", request);
        updateStringProperty(appearance, "timezone", request);
        updateStringProperty(appearance, "dateFormat", request);
        updateStringProperty(appearance, "currency", request);
        updateStringProperty(appearance, "theme", request);
        updateStringProperty(appearance, "accentColor", request);
        updateStringProperty(appearance, "density", request);
        
        // Add lastUpdated timestamp to the appearance object
        appearance.addProperty("lastUpdated", currentTimestamp);
        System.out.println("Updated appearance for user: " + user.get("username").getAsString());
    }
    
    private void updateListings(JsonObject user, HttpServletRequest request) {
        JsonObject listings;
        
        if (user.has("listings")) {
            listings = user.getAsJsonObject("listings");
        } else {
            listings = new JsonObject();
            user.add("listings", listings);
        }
        
        updateStringProperty(listings, "defaultPropertyType", request);
        updateStringProperty(listings, "displayCurrency", request);
        updateStringProperty(listings, "unitSystem", request);
        updateStringProperty(listings, "defaultRadius", request);
        
        // Handle fields data which is sent as a JSON string
        String fieldsJson = request.getParameter("fields");
        if (fieldsJson != null && !fieldsJson.isEmpty()) {
            try {
                JsonObject fields = gson.fromJson(fieldsJson, JsonObject.class);
                listings.add("fields", fields);
                System.out.println("Added fields data: " + fieldsJson);
            } catch (Exception e) {
                logger.warning("Invalid fields JSON format: " + e.getMessage());
                System.err.println("Invalid fields JSON format: " + e.getMessage());
                throw new IllegalArgumentException("Invalid fields data format: " + e.getMessage());
            }
        }
        
        // Add lastUpdated timestamp to the listings object
        listings.addProperty("lastUpdated", currentTimestamp);
        System.out.println("Updated listings for user: " + user.get("username").getAsString());
    }
    
    private void updateStringProperty(JsonObject obj, String property, HttpServletRequest request) {
        String value = request.getParameter(property);
        if (value != null) {
            obj.addProperty(property, value);
            System.out.println("Updated property '" + property + "' to '" + value + "'");
        }
    }
    
    private void updateBooleanProperty(JsonObject obj, String property, HttpServletRequest request) {
        String value = request.getParameter(property);
        if (value != null) {
            boolean boolValue = Boolean.parseBoolean(value);
            obj.addProperty(property, boolValue);
            System.out.println("Updated boolean property '" + property + "' to '" + boolValue + "'");
        }
    }
    
    /**
     * Returns the current timestamp in the format yyyy-MM-dd HH:mm:ss
     */
    private String getCurrentDateTime() {
        try {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            logger.warning("Error formatting current date/time: " + e.getMessage());
            System.err.println("Error formatting current date/time: " + e.getMessage());
            return "2025-03-23 17:34:57"; // Fallback timestamp
        }
    }
    
    private String getFileExtension(Part part) {
        String contentDisposition = part.getHeader("content-disposition");
        String[] elements = contentDisposition.split(";");
        
        for (String element : elements) {
            if (element.trim().startsWith("filename")) {
                String fileName = element.substring(element.indexOf('=') + 1).trim().replace("\"", "");
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot > 0) {
                    return fileName.substring(lastDot);
                }
            }
        }
        
        // Default extension based on content type
        String contentType = part.getContentType();
        if (contentType != null) {
            if (contentType.equals("image/jpeg") || contentType.equals("image/jpg")) return ".jpg";
            if (contentType.equals("image/png")) return ".png";
            if (contentType.equals("image/gif")) return ".gif";
            if (contentType.equals("image/webp")) return ".webp";
        }
        
        return ".jpg";
    }
}