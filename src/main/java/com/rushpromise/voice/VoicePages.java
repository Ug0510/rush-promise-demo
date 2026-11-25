package com.rushpromise.voice;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("!worker")
class VoicePages {
    @GetMapping("/caller")
    String caller() {
        return "forward:/voice-ui/caller.html";
    }

    @GetMapping("/staff")
    String staff() {
        return "forward:/staff-ui/staff.html";
    }
}
