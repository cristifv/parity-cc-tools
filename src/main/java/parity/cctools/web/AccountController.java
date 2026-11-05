package parity.cctools.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.security.Capability;
import parity.cctools.security.PublicEndpoint;
import parity.cctools.security.Requires;

/** Me (roles, auth time, logout) and the two pages shown without a login. */
@Controller
public class AccountController {

    @GetMapping("/me")
    @Requires(Capability.HEALTH_READ)
    public String me() {
        return "pages/me";
    }

    @GetMapping("/login-refused")
    @PublicEndpoint("shown when the ops realm's login or token was refused: there is no session to check")
    public String refused(@RequestParam(required = false) String reason, Model model) {
        String r = reason != null && reason.matches("^[a-z_]{1,40}$") ? reason : "login_failed";
        model.addAttribute("reason", r);
        model.addAttribute("explanation", switch (r) {
            case "no_otp" -> "This tool needs a login with your one-time code (TOTP). Sign in again with it.";
            case "no_ops_role" -> "Your account doesn't have the ops role.";
            case "wrong_issuer", "wrong_audience", "invalid_id_token" -> "That login wasn't from the ops realm.";
            case "session_revoked" -> "Your session ended in the ops realm (signed out, disabled, or the role was removed).";
            default -> "The login didn't complete.";
        });
        return "pages/login-refused";
    }

    @GetMapping("/logged-out")
    @PublicEndpoint("the page after the ops realm's logout redirect: the session is already gone")
    public String loggedOut() {
        return "pages/logged-out";
    }
}
