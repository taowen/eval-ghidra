import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
/** Give a verified virtual override its own prototype without changing its
 * tail-branch instructions or the shared allocator callee's signature.
 * Arguments are exact entry and expected direct thunk target VAs. */
public class MaterializeThunkOverride extends GhidraScript {
 public void run() throws Exception {
  String[] a=getScriptArgs();
  if(a.length!=2)throw new IllegalArgumentException("entryVA directTargetVA");
  Function f=getFunctionAt(toAddr(a[0]));
  if(f==null||!f.isThunk())throw new IllegalStateException("not an exact thunk entry");
  Function target=f.getThunkedFunction(false);
  if(target==null||!target.getEntryPoint().equals(toAddr(a[1])))throw new IllegalStateException("unexpected target: "+target);
  println("entry="+f.getEntryPoint()+" target="+target.getEntryPoint()+" body="+f.getBody());
  f.setThunkedFunction(null);
  println("independent_prototype="+!f.isThunk()+" target_signature="+target.getSignature());
 }
}
