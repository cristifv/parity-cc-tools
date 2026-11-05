package parity.cctools.mi;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import parity.cctools.config.CcToolsProperties;

/** Each zone's opensips SBC nodes' management interfaces ({@code parity-cc-tools.zones.<zone>.opensips.mi-urls}). */
@Component
public class OpensipsMis {

    private final Map<String, List<OpensipsMi>> byZone = new TreeMap<>();

    public OpensipsMis(CcToolsProperties properties) {
        properties.zones().forEach((zone, z) -> {
            if (z.opensips() != null && !z.opensips().miUrls().isEmpty()) {
                List<OpensipsMi> nodes = new ArrayList<>();
                for (URI u : z.opensips().miUrls()) {
                    nodes.add(new OpensipsMi(zone, u, z.opensips()));
                }
                byZone.put(zone, List.copyOf(nodes));
            }
        });
    }

    /** The zone's SBC nodes; empty when none is configured. */
    public List<OpensipsMi> zone(String zone) {
        return zone == null ? List.of() : byZone.getOrDefault(zone, List.of());
    }
}
