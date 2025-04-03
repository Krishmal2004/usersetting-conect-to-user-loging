package com.RealState.servlets;
 
import com.RealState.services.UserAuthService;
 
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
 
public class UserLoginServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private final UserAuthService authService = new UserAuthService();
 
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // Simply forward to the login page
        request.getRequestDispatcher("UserLogin.jsp").forward(request, response);
    }
 
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
 
        String username = request.getParameter("username");
        String password = request.getParameter("password");
 
        // Pass the ServletContext as the third parameter
        if (authService.authenticateUser(username, password, getServletContext())) {
            // Success - create session and redirect to admin dashboard
            HttpSession session = request.getSession();
            session.setAttribute("Username", username);
            session.setAttribute("isUser", true);
 
            response.sendRedirect("userDashboard.jsp");
        } else {
            // Failed login
            request.setAttribute("error", "Invalid username or password");
            request.getRequestDispatcher("UserLogin.jsp").forward(request, response);
        }
    }
}