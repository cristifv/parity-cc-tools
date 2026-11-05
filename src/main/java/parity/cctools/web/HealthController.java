package parity.cctools.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import parity.cctools.health.HealthCollector;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;

/** The platform health dashboard (PLAN.md §4.8): problems first; the table refreshes every 10 s. */
@Controller
public class HealthController {

    private final HealthCollector health;

    public HealthController(HealthCollector health) {
        this.health = health;
    }

    @GetMapping("/health")
    @Requires(Capability.HEALTH_READ)
    public String page(Model model) {
        model.addAttribute("snapshot", health.snapshot());
        return "pages/health";
    }

    @GetMapping("/health/table")
    @Requires(Capability.HEALTH_READ)
    public String table(Model model) {
        model.addAttribute("snapshot", health.snapshot());
        return "fragments/health :: table";
    }
}
