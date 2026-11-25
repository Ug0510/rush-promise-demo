package com.rushpromise.voice;

import com.rushpromise.platform.PlatformContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("!worker")
class OwnerPages {
    @GetMapping("/owner")
    String owner() {
        return "forward:/owner-ui/owner.html";
    }

    @GetMapping("/owner/desk")
    String desk(HttpServletRequest request) {
        return PlatformContextFilter.ownerHasShop(request) ? "forward:/staff-ui/staff.html" : "redirect:/owner";
    }
}
