import ghidra.app.script.GhidraScript;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.options.ToolOptions;

/**
 * Pin the GhidraMCP "Server Port" tool option for this tool and save it.
 *
 * Why: the port is a Tool Option. Unless a value has been saved once, the
 * plugin falls back to DEFAULT_PORT and, if that is taken, to the next free
 * port in its fallback range. Writing the option here makes the second Ghidra
 * instance keep a stable, distinct port across launches.
 *
 * Args: port. Read-only with respect to the program.
 */
public class PinMcpPort extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("port");
        int port = Integer.parseInt(args[0]);

        PluginTool tool = state.getTool();
        if (tool == null) throw new IllegalStateException("no tool context");
        ToolOptions options = tool.getOptions("GhidraMCP HTTP Server");
        int before = options.getInt("Server Port", -1);
        options.setInt("Server Port", port);
        int after = options.getInt("Server Port", -1);
        println("server_port before=" + before + " after=" + after);

        // Ask the tool to persist its option state so the value survives restart.
        tool.saveTool();
        println("tool saved");
    }
}
