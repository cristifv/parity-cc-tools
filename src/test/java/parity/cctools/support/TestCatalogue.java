package parity.cctools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The ITs' service catalogue (as parity-deploy would render it), pointing at the stand-ins. */
public final class TestCatalogue {

    private TestCatalogue() {}

    public static Path write(String name, int kubePort) throws IOException {
        String yaml = """
                parity-cc-tools:
                  zones:
                    eu1a:
                      services:
                        - name: gateway
                          namespace: parity-app
                          selector: app.kubernetes.io/name=gateway
                          kind: deployment
                          workload: gateway
                          k8s-service: gateway
                          port: %1$d
                          metrics:
                            - name: telephony_controller_sessions
                              title: sessions
                          drivers: [k8s-rotation]
                          call-path: true
                        - name: worker
                          namespace: parity-app
                          selector: app.kubernetes.io/name=worker
                          kind: deployment
                          workload: worker
                          port: %1$d
                          drivers: [k8s-scale]
                          call-path: false
                          default-replicas: 2
                      media-nodes:
                        - name: media-1
                          namespace: parity-media
                          pod-selector: parity.media/node=media-1
                          dispatcher:
                            - { set: 1, address: "sip:10.0.0.5:5080" }
                            - { set: 2, address: "sip:10.0.0.5:5090" }
                          gauge:
                            pod-selector: "app.kubernetes.io/name=telephony-controller,parity.media/node=media-1"
                            port: %1$d
                            path: /metrics
                            metric: telephony_controller_sessions
                          call-path: true
                        - name: media-2
                          namespace: parity-media
                          pod-selector: parity.media/node=media-2
                          dispatcher:
                            - { set: 1, address: "sip:10.0.0.6:5080" }
                          gauge:
                            pod-selector: "app.kubernetes.io/name=telephony-controller,parity.media/node=media-2"
                            port: %1$d
                            metric: telephony_controller_sessions
                """.formatted(kubePort);
        Path f = Path.of("target", name + "-catalogue.yaml").toAbsolutePath();
        Files.writeString(f, yaml);
        return f;
    }
}
