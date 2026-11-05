package parity.cctools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The template rules (PLAN.md §7), as a build check: escaping only ({@code th:utext} banned),
 * no inline script or style (the CSP would block them anyway), no event-handler attributes, no
 * {@code javascript:} URLs, no third-party script (PLAN.md §7).
 */
class TemplateRulesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Pattern UTEXT = Pattern.compile("th:utext|data-th-utext|th:remove=\"tag\"\\s+th:text");
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)(?![^>]*\\bth:src=)[^>]*>");
    private static final Pattern STYLE = Pattern.compile("<style\\b|\\sstyle=|th:style=");
    private static final Pattern HANDLER = Pattern.compile("\\son[a-z]+\\s*=|th:on[a-z]+=|hx-on");
    private static final Pattern JS_URL = Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_SCRIPT = Pattern.compile("<script[^>]*\\bsrc=\"(https?:)?//([^/\"]+)");

    @Test
    void templatesFollowTheRules() throws IOException {
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String t = Files.readString(f);
                String name = TEMPLATES.relativize(f).toString();
                if (UTEXT.matcher(t).find()) {
                    broken.add(name + ": th:utext");
                }
                if (INLINE_SCRIPT.matcher(t).find()) {
                    broken.add(name + ": inline script");
                }
                if (STYLE.matcher(t).find()) {
                    broken.add(name + ": inline style");
                }
                if (HANDLER.matcher(t).find()) {
                    broken.add(name + ": event handler attribute");
                }
                if (JS_URL.matcher(t).find()) {
                    broken.add(name + ": javascript: URL");
                }
                var ext = EXTERNAL_SCRIPT.matcher(t);
                while (ext.find()) {
                    broken.add(name + ": third-party script from " + ext.group(2));
                }
            }
        }
        assertThat(broken).isEmpty();
    }
}
