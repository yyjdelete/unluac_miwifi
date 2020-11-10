package unluac.parse;

import java.util.Objects;

public class LUpvalue extends BObject {

  public int instack;
  public int idx;
  
  public String name;
  public LString bname;
  public int kind;
  
  public boolean equals(Object obj) {
    if(obj instanceof LUpvalue) {
      LUpvalue upvalue = (LUpvalue) obj;
      if(!(instack == upvalue.instack && idx == upvalue.idx && kind == upvalue.kind)) {
        return false;
      }
      return Objects.equals(name, upvalue.name);
    } else {
      return false;
    }
  }
  
}
