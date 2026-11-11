package web.config;

import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

public class AppInit extends AbstractAnnotationConfigDispatcherServletInitializer {

    // This small application uses only the DispatcherServlet context.
    @Override
    protected Class<?>[] getRootConfigClasses() {
        return null;
    }
    // Configure Spring MVC and the Thymeleaf view resolver.
    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[]{WebConfig.class};
    }
    // Map requests within the application's context path to DispatcherServlet.
    @Override
    protected String[] getServletMappings() {
        return new String[]{"/"};
    }
}
