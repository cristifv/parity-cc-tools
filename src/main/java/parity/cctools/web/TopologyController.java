package parity.cctools.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import parity.cctools.routing.RsClient;
import parity.cctools.routing.RsRequests;
import parity.cctools.security.Capability;
import parity.cctools.security.Requires;

/** The topology (PLAN.md §4.10), read-only, as parity-rs's {@code get_topology} answers it. */
@Controller
public class TopologyController {

    private final RsClient rs;
    private final TenantsController tenants;

    public TopologyController(RsClient rs, TenantsController tenants) {
        this.rs = rs;
        this.tenants = tenants;
    }

    @GetMapping("/topology")
    @Requires(Capability.ROUTING_READ)
    public String topology(@RequestParam(required = false) String zone, Model model) {
        String z = tenants.zoneOr(zone);
        var t = rs.read(z, RsRequests.getTopology());
        model.addAttribute("zone", z);
        model.addAttribute("topology", t);
        model.addAttribute("regions", t.path("regions"));
        model.addAttribute("zonesNode", t.path("zones"));
        model.addAttribute("units", tenants.units(z));
        return "pages/topology";
    }
}
