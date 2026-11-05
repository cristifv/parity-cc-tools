package parity.cctools.k8s;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import parity.cctools.config.CcToolsProperties;
import parity.cctools.obs.ToolMetrics;

/** Each zone's Kubernetes API ({@code parity-cc-tools.zones.<zone>.kubernetes}); only zones with it enabled. */
@Component
public class KubeApis {

    private final Map<String, KubeApi> byZone = new TreeMap<>();

    public KubeApis(CcToolsProperties properties, ToolMetrics metrics) {
        properties.zones().forEach((zone, z) -> {
            if (z.kubernetes() != null && z.kubernetes().enabled()) {
                byZone.put(zone, new KubeApi(zone, z.kubernetes(), metrics));
            }
        });
    }

    public Optional<KubeApi> zone(String zone) {
        return Optional.ofNullable(zone == null ? null : byZone.get(zone));
    }
}
