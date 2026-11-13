import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jface.text.Document;

/**
 * Command-line adapter for the formatter bundled with the VS Code Java
 * extension.
 */
public class JavaFormatter {
    public static void main(String[] args) throws Exception {
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(args[0]);
        var nodes = xml.getElementsByTagName("setting");
        var settings = new HashMap<String, String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var attributes = nodes.item(i).getAttributes();
            settings.put(attributes.getNamedItem("id").getNodeValue(), attributes.getNamedItem("value").getNodeValue());
        }
        settings.put("org.eclipse.jdt.core.compiler.source", "21");
        settings.put("org.eclipse.jdt.core.compiler.compliance", "21");
        settings.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "21");
        boolean check = Boolean.parseBoolean(args[1]);
        boolean changed = false;
        for (int i = 2; i < args.length; i++) {
            Path path = Path.of(args[i]);
            String source = Files.readString(path);
            var formatter = new DefaultCodeFormatter(settings);
            var edits = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                    source, 0, source.length(), 0, "\n");
            if (edits == null) {
                throw new IllegalArgumentException("Cannot format Java source: " + path);
            }
            var document = new Document(source);
            edits.apply(document);
            if (!source.equals(document.get())) {
                changed = true;
                System.out.println((check ? "Needs formatting: " : "Formatted: ") + path);
                if (!check) {
                    Files.writeString(path, document.get());
                }
            }
        }
        if (check && changed) {
            System.exit(1);
        }
    }
}
