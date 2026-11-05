package parity.cctools.catalogue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The service catalogue of every configured zone ({@code parity-cc-tools.zones.<zone>.services}). */
public final class Catalogues {

    private final Map<String, Catalogue> byZone;

    public Catalogues(Map<String, Catalogue> byZone) {
        this.byZone = Map.copyOf(byZone);
    }

    public Optional<Catalogue> zone(String zone) {
        return Optional.ofNullable(zone == null ? null : byZone.get(zone));
    }

    public List<String> zones() {
        return byZone.keySet().stream().sorted().toList();
    }
}
