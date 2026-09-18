import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Remove only independently disproven comment lines from a reviewed JSON manifest.
 * No function addresses or exception rules are embedded. Validate every entry
 * before changing any comments; a stale manifest fails without partial edits. */
public class RemoveExactCommentLines extends GhidraScript {
 public void run() throws Exception {
  String[] args=getScriptArgs();
  if(args.length!=1) throw new IllegalArgumentException("manifest.json");
  JsonArray entries=JsonParser.parseString(Files.readString(Paths.get(args[0]))).getAsJsonArray();
  List<Address> addresses=new ArrayList<>();
  List<String> replacements=new ArrayList<>();
  for(JsonElement element:entries) {
   JsonObject e=element.getAsJsonObject();
   if(!e.get("comment_type").getAsString().equals("PRE")) throw new IllegalArgumentException("only PRE supported");
   Address a=toAddr(e.get("address").getAsString());
   String old=currentProgram.getListing().getComment(CodeUnit.PRE_COMMENT,a);
   if(!Objects.equals(old,e.get("expected_full_comment").getAsString())) throw new IllegalStateException("comment changed: "+a);
   List<String> lines=new ArrayList<>(Arrays.asList(old.split("\n",-1)));
   for(JsonElement line:e.getAsJsonArray("remove_exact_lines"))
    if(!lines.remove(line.getAsString())) throw new IllegalStateException("line missing: "+a);
   addresses.add(a); replacements.add(lines.isEmpty()?null:String.join("\n",lines));
  }
  for(int i=0;i<addresses.size();i++) {
   currentProgram.getListing().setComment(addresses.get(i),CodeUnit.PRE_COMMENT,replacements.get(i));
   println("updated PRE at "+addresses.get(i));
  }
 }
}
