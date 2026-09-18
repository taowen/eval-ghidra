// Inspect one imported structure/union from the active program's DTM.
// @category Tutorial
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.Composite;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeComponent;

public class InspectType extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("type name required");
        DataType selected = currentProgram.getDataTypeManager().getDataType("/" + args[0]);
        if (selected == null) throw new IllegalArgumentException("missing type /" + args[0]);
        println(selected.getPathName() + " size=" + selected.getLength()
                + " align=" + selected.getAlignment());
        if (!(selected instanceof Composite)) return;
        for (DataTypeComponent component : ((Composite)selected).getDefinedComponents()) {
            println(String.format("%04x size=%d %-32s %s",
                    component.getOffset(), component.getLength(),
                    component.getFieldName(), component.getDataType().getDisplayName()));
        }
    }
}
