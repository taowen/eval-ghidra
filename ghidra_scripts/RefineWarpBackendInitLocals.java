// @category Tutorial
// Actual entry-SP stack lifetimes for the two normal graphics initializers.
// Scratch unions belong here because they describe reused compiler stack slots,
// not a runtime object layout. Native code uses standard strings/containers.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import java.util.*;

public class RefineWarpBackendInitLocals extends GhidraScript {
    private DataType named(String name, int length) {
        Iterator<DataType> all=currentProgram.getDataTypeManager().getAllDataTypes();
        while(all.hasNext()) {
            DataType t=all.next();
            if(t.getName().equals(name) && t.getLength()==length) return t;
        }
        throw new IllegalStateException("Missing " + name + " length=" + length);
    }
    private DataType union(String name, DataType... types) {
        UnionDataType u=new UnionDataType(new CategoryPath("/tutorial/stack"),name);
        for(int i=0;i<types.length;++i) u.add(types[i],"view"+i,null);
        return currentProgram.getDataTypeManager().addDataType(u,DataTypeConflictHandler.REPLACE_HANDLER);
    }
    private void stack(long rva,int offset,String name,DataType type) throws Exception {
        Function f=getFunctionAt(toAddr(rva+0x100000L));
        if(f==null) throw new IllegalStateException("Missing exact entry");
        int end=offset+type.getLength();
        for(Variable v:f.getLocalVariables()) {
            if(!v.isStackVariable()) continue;
            int start=v.getStackOffset();
            if(start<end && start+v.getLength()>offset) f.removeVariable(v);
        }
        f.getStackFrame().createVariable(name,offset,type,SourceType.USER_DEFINED);
        println(f.getName()+" "+name+" "+offset+" "+type.getLength());
    }
    @Override public void run() throws Exception {
        named("TutorialWarpGraphicsBackend",0x4d40);
        String[][] vectors={{"TutorialWarpAttributeVector","TutorialWarpAttribute"},
            {"TutorialWarpStringVector","TutorialRuntimeString"},{"TutorialWarpCropVector","TutorialWarpCropHandle"},
            {"TutorialWarpCropRows","TutorialWarpCropVector"}};
        for(String[] pair:vectors) {
            Structure vector=(Structure)named(pair[0],24);
            for(int offset=0;offset<24;offset+=8) {
                DataType field=vector.getComponentAt(offset).getDataType();
                if(!(field instanceof Pointer) ||
                    !((Pointer)field).getDataType().getName().equals(pair[1]))
                    throw new IllegalStateException("Wrong vector pointer depth: "+pair[0]+"+"+offset);
            }
        }
        DataType string=named("TutorialRuntimeString",24);
        DataType logger=named("TutorialLoggerHandle",16);
        DataType handle=named("TutorialSharedHandle",16);
        DataType source=named("TutorialLogSourceWords",24);
        DataType surface=named("TutorialRenderSurfaceHandle",16);
        DataType state=named("TutorialGlesStateHandle",16);
        DataType attribute=named("TutorialWarpAttribute",40);
        DataType scratch=union("TutorialWarpBackendHandleStringLogScratch",string,handle,logger,source);
        DataType attributeScratch=union("TutorialWarpBackendAttributeLogScratch",attribute,logger);
        // 1980e28: SP=entry-130, FP=entry-60. Factory/log/string scratch
        // is FP-30, outer/path scratch SP50, and logger/outer scratch SP40.
        stack(0x1980e28L,-0x90,"uFactoryStringLog",scratch);
        stack(0x1980e28L,-0xe0,"uOuterPathLogger",scratch);
        stack(0x1980e28L,-0xf0,"stOuterOrLogger",union("TutorialWarpBackendOuterLoggerScratch",handle,logger));
        stack(0x1980e28L,-0x128,"stBufferSurface",surface);
        stack(0x1980e28L,-0x110,"stTextureSurface",surface);
        stack(0x1980e28L,-0x100,"stTexture",handle);
        stack(0x1980e28L,-0xc8,"stStaticImageSurface",surface);
        stack(0x1980e28L,-0xb8,"stSecondSamplerSurface",surface);
        stack(0x1980e28L,-0xa8,"stFirstSamplerSurface",surface);
        // 19c5914: initial70B save +220B locals; FP=entry-60.
        stack(0x19c5914L,-0x150,"uPositionAttributeOrLogger",attributeScratch);
        stack(0x19c5914L,-0x180,"stTexcoordAttribute",attribute);
        stack(0x19c5914L,-0x1b0,"stMatrixIndexAttribute",attribute);
        stack(0x19c5914L,-0x1e0,"stBorderAttribute",attribute);
        stack(0x19c5914L,-0x210,"stCylinderIndexAttribute",attribute);
        stack(0x19c5914L,-0x120,"uNameOrLogSource",union("TutorialWarpBackendNameLogScratch",string,source));
        stack(0x19c5914L,-0x108,"szIndexedName",new ArrayDataType(CharDataType.dataType,128,1));
        stack(0x19c5914L,-0x230,"stStateInput",state);
        stack(0x19c5914L,-0x220,"stSurfaceInput",surface);
    }
}
