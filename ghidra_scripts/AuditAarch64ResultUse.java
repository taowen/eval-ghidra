import ghidra.app.script.GhidraScript;

/** Read-only first-use triage for AArch64 x0/w0 after calls to an exact entry.
 * Argument is Ghidra VA, not ELF RVA. Tail transfers have no local continuation.
 * Stop at branches, calls, missing instructions or function boundaries rather
 * than interpreting adjacent functions as result users. "returned" only means
 * register preservation, never proof of a source-level return contract.
 * This bounded linear scan deliberately leaves CFG/EH paths unresolved; it is
 * not a whole-program liveness, caller ABI or translation-readiness proof.
 */
public class AuditAarch64ResultUse extends GhidraScript {
    @Override public void run() throws Exception {
String[] args = getScriptArgs();
if (args.length != 1) throw new IllegalArgumentException("true entry Ghidra VA required");
var entry = toAddr(Long.decode(args[0]));
if (getFunctionAt(entry) == null) throw new IllegalArgumentException("not a function entry");
var refs = currentProgram.getReferenceManager().getReferencesTo(entry);
int tail=0, overwritten=0, returned=0, reads=0, unresolved=0;
while(refs.hasNext()) {
 monitor.checkCancelled();
 var ref=refs.next(); if(!ref.getReferenceType().isCall()) continue;
 var ins=getInstructionAt(ref.getFromAddress()); if(ins==null) continue;
 var owner=getFunctionContaining(ins.getAddress());
 if(ins.getMnemonicString().equalsIgnoreCase("b")) {tail++; continue;}
 String result="unresolved"; String evidence="";
 var next=ins.getFallThrough();
 for(int n=0;n<32 && next!=null;n++) {
  var i=getInstructionAt(next);
  if(i==null || owner==null || !owner.getBody().contains(next)) break;
  evidence += i.getAddress()+" "+i+" | ";
  if(i.getMnemonicString().equalsIgnoreCase("ret")) { result="returned"; break; }
  boolean read=false,write=false;
  for(Object o:i.getInputObjects()) if(o instanceof ghidra.program.model.lang.Register) {
   String name=((ghidra.program.model.lang.Register)o).getName();
   if(name.equalsIgnoreCase("x0")||name.equalsIgnoreCase("w0"))read=true;
  }
  for(Object o:i.getResultObjects()) if(o instanceof ghidra.program.model.lang.Register) {
   String name=((ghidra.program.model.lang.Register)o).getName();
   if(name.equalsIgnoreCase("x0")||name.equalsIgnoreCase("w0"))write=true;
  }
  if(read){result="read";break;}
  if(write){result="overwritten";break;}
  if(i.getFlowType().isCall()||i.getFlowType().isJump()||i.getFlowType().isTerminal())break;
  next=i.getFallThrough();
 }
 if(result.equals("overwritten"))overwritten++;else if(result.equals("returned"))returned++;else if(result.equals("read"))reads++;else unresolved++;
 println(ins.getAddress()+" "+result+" "+evidence);
}
println("SUMMARY tail="+tail+" overwritten="+overwritten+" returned="+returned+" reads="+reads+" unresolved="+unresolved);

    }
}
