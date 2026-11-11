package web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class HelloController {

    @GetMapping("/")
    public String printWelcome(ModelMap model) {
        List<String> messages = List.of(
                "Congratulations!",
                "You just launched a Spring MVC application :)",
                "Приложение Spring MVC запущено!"
        );
        model.addAttribute("messages", messages);
        return "index";
    }
}
