package dev.protegemcp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLEditorKitHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Protégé creates one hook per window. The windows share one HTTP server. */
public class ProtegeMcpHook extends OWLEditorKitHook {
    private static final Logger log = LoggerFactory.getLogger(ProtegeMcpHook.class);
    private static final List<OWLEditorKit> KITS = new CopyOnWriteArrayList<>();
    private static McpServer server;

    private OWLEditorKit kit;

    static List<OWLEditorKit> kits() {
        return KITS;
    }

    @Override
    public void initialise() {
        kit = (OWLEditorKit) getEditorKit();
        KITS.add(kit);
        synchronized (ProtegeMcpHook.class) {
            if (server == null) {
                try {
                    server = McpServer.start(new Tools());
                } catch (Exception e) {
                    log.error("[protege-mcp] Server did not start: {}", e.toString());
                }
            }
        }
    }

    @Override
    public void dispose() {
        KITS.remove(kit);
        synchronized (ProtegeMcpHook.class) {
            if (KITS.isEmpty() && server != null) {
                server.stop();
                server = null;
            }
        }
    }
}
