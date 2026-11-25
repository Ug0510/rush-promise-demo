package com.rushpromise.voice;

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
}
