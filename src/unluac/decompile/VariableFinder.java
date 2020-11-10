package unluac.decompile;

import java.util.ArrayList;
import java.util.List;

import unluac.Version;
import unluac.parse.LFunction;
import unluac.parse.LUpvalue;

public class VariableFinder {

  static class RegisterState {
    
    public RegisterState() {
      last_written = 1;
      last_read = -1;
      read_count = 0;
      temporary = false;
      local = false;
      read = false;
      written = false;
    }
    
    int last_written;
    int last_read;
    int read_count;
    boolean temporary;
    boolean local;
    boolean read;
    boolean written;
  }
  
  static class RegisterStates {
    
    RegisterStates(int registers, int lines) {
      this.registers = registers;
      this.lines = lines;
      states = new RegisterState[lines][registers];
      for(int line = 0; line < lines; line++) {
        for(int register = 0; register < registers; register++) {
          states[line][register] = new RegisterState();
        }
      }
    }
    
    public RegisterState get(int register, int line) {
      return states[line - 1][register];
    }
    
    public void setWritten(int register, int line) {
      if (get(register, line).written) return;
      get(register, line).written = true;
      get(register, line + 1).last_written = line;
    }
    
    public void setRead(int register, int line) {
      if (get(register, line).read) return;
      get(register, line).read = true;
      get(register, get(register, line).last_written).read_count++;
      get(register, get(register, line).last_written).last_read = line;
    }

    //why set range???
    public void setLocalRead(int register, int line) {
      setRead(register, line);
      get(register, get(register, line).last_written).local = true;
    }

    //???
    public void setLocalWrite(int register, int line) {
      setWritten(register, line);
      get(register, line).local = true;
    }

    //???
    public void setTemporaryRead(int register, int line) {
      setRead(register, line);
      get(register, get(register, line).last_written).temporary = true;
    }

    //???
    public void setTemporaryWrite(int register, int line) {
      setWritten(register, line);
      get(register, line).temporary = true;
    }
    
    public void nextLine(int line) {
      if(line + 1 < lines) {
        for(int r = 0; r < registers; r++) {
          if(get(r, line).last_written > get(r, line + 1).last_written) {
            get(r, line + 1).last_written = get(r, line).last_written;
          }
        }
      }
    }
    
    private int registers;
    private int lines;
    private RegisterState[][] states;
    
  }
  
  private static boolean isConstantReference(Decompiler d, int value) {
    return d.function.header.extractor.is_k(value);
  }
  
  public static Declaration[] process(Decompiler d, int args, int registers) {
    Code code = d.code;
    RegisterStates states = new RegisterStates(registers, code.length());
    boolean[] skip = new boolean[code.length()];
    for(int line = 1; line <= code.length(); line++) {
      states.nextLine(line);
      if(skip[line - 1]) continue;

      int A = code.A(line);
      int B = code.B(line);
      int C = code.C(line);
      Op opcode = code.op(line);
      if (opcode == Op.MAGIC) {
        if (C == 0) {
          opcode = Op.CLOSE;
        } else if (C == 1) {
          opcode = Op.LEN;
        } else if (C == 2) {
          opcode = Op.UNM;
        } else if (C == 3) {
          opcode = Op.NOT;
        }
      }
      switch(opcode) {
        case MOVE:
          states.setWritten(A, line);
          states.setRead(B, line);
          //if(A < B) {
          //  states.setLocalWrite(A, line);
          //} else if(B < A) {
          //  states.setLocalRead(B, line);
          //}
          break;
        case LOADK:
        case LOADBOOL:
        case GETUPVAL:
        case GETGLOBAL:
        case NEWTABLE:
        case NEWTABLE50:
          states.setWritten(A, line);
          break;
        case LOADNIL: {
          int maximum = B;
          int register = A;
          while(register <= maximum) {
            states.setWritten(register, line);
            register++;
          }
          break;
        }
        case LOADNIL52: {
          int maximum = A + B;
          int register = A;
          while(register <= maximum) {
            states.setWritten(register, line);
            register++;
          }
          break;
        }
        case GETTABLE:
          states.setWritten(A, line);
          if(!isConstantReference(d, B)) states.setRead(B, line);
          if(!isConstantReference(d, C)) states.setRead(C, line);
          break;
        case SETGLOBAL:
        case SETUPVAL:
          states.setRead(A, line);
          break;
        case SETTABLE:
        case ADD:
        case SUB:
        case MUL:
        case DIV:
        case MOD:
        case POW:
          states.setWritten(A, line);
          if(!isConstantReference(d, B)) states.setRead(B, line);
          if(!isConstantReference(d, C)) states.setRead(C, line);
          break;
        case SELF:
          states.setWritten(A, line);
          states.setWritten(A + 1, line);
          states.setRead(B, line);
          if(!isConstantReference(d, C)) states.setRead(C, line);
          break;
        case UNM:
        case NOT:
        case LEN:
          states.setWritten(A, line);
          states.setRead(B, line);
          break;
        case CONCAT:
          states.setWritten(A, line);
          for(int register = B; register <= C; register++) {
            states.setRead(register, line);
            states.setTemporaryRead(register, line);
          }
          break;
        case SETLIST:
          for (int idx = 1; idx <= B; ++idx) {
            states.setTemporaryRead(A + idx, line);
          }
          break;
        case JMP:
        case JMP52:
          break;
        case EQ:
        case LT:
        case LE:
          if(!isConstantReference(d, B)) states.setRead(B, line);
          if(!isConstantReference(d, C)) states.setRead(C, line);
          break;
        case TEST:
          states.setRead(A, line);
          break;
        case TESTSET:
          states.setWritten(A, line);
          states.setLocalWrite(A, line);
          states.setRead(B, line);
          break;
        case CLOSURE: {
          LFunction f = d.function.functions[code.Bx(line)];
          for(LUpvalue upvalue : f.upvalues) {
            if(upvalue.instack == 1) {
              states.setLocalRead(upvalue.idx, line);
            }
          }
          if(f.header.version.upvaluedeclarationtype.get() == Version.UpvalueDeclarationType.INLINE) {
            // Skip upvalue declarations
            for(int i = 0; i < f.numUpvalues; i++) {
              skip[line + i] = true;
            }
          }
          states.setWritten(A, line);
          break;
        }
        case CALL:
        case TAILCALL: {
          if(code.op(line) != Op.TAILCALL) {
            if(C >= 2) {
              for(int register = A; register <= A + C - 2; register++) {
                states.setWritten(register, line);
              }
            }
          }
          for(int register = A; register <= A + B - 1; register++) {
            states.setRead(register, line);
            states.setTemporaryRead(register, line);
          }
          if(C >= 2) {
            int nline = line + 1;
            int register = A + C - 2;
            while(register >= A && nline <= code.length()) {
              if(code.op(nline) == Op.MOVE && code.B(nline) == register) {
                states.setWritten(code.A(nline), nline);
                states.setRead(code.B(nline), nline);
                states.setLocalWrite(code.A(nline), nline);
                skip[nline - 1] = true;

                register--;
                nline++;
              } else {
                break;
              }
            }
          }
          break;
        }
        case RETURN: {
          if(B == 0) B = registers - A + 1;
          for(int register = A; register <= A + B - 2; register++) {
            states.setRead(register, line);
          }
          break;
        }
        default:
          System.err.println("--Unhandled op in VariableFinder: " + code.op(line));
          break;
      }
    }

    //for(int register = 0; register < registers; register++) {
    //  states.setWritten(register, 1);
    //}
    for(int line = 1; line <= code.length(); line++) {
      for(int register = 0; register < registers; register++) {
        RegisterState s = states.get(register, line);
        if(s.written) {
          if(s.read_count >= 2/* || (line >= 2 && s.read_count == 0)*/) {
            states.setLocalWrite(register, line);
          }
        }
      }
    }
    //TODO: check this

    for(int line = 1; line <= code.length(); line++) {
      for(int register = 0; register < registers; register++) {
        RegisterState s = states.get(register, line);
        if(s.written && s.temporary) {
          List<Integer> ancestors = new ArrayList<Integer>();
          for(int read = 0; read < registers; read++) {
            RegisterState r = states.get(read, line);
            if(r.read && !r.local) {
              ancestors.add(read);
            }
          }
          int pline;
          for(pline = line - 1; pline >= 1; pline--) {
            boolean any_written = false;
            for(int pregister = 0; pregister < registers; pregister++) {
              if(states.get(pregister, pline).written && ancestors.contains(pregister)) {
                any_written = true;
                ancestors.remove((Object)pregister);
              }
            }
            if(!any_written) {
              break;
            }
            for(int pregister = 0; pregister < registers; pregister++) {
              RegisterState a = states.get(pregister, pline);
              if(a.read && !a.local) {
                ancestors.add(pregister);
              }
            }
          }
          for(int ancestor : ancestors) {
            if(pline >= 1) {
              states.setLocalRead(ancestor, pline);
            }
          }
        }
      }
    }

    if (false){
      for(int register = 0; register < registers; register++) {
        for(int line = 1; line <= code.length(); line++) {
          RegisterState s = states.get(register, line);
          if(s.written || s.read_count > 0 || s.temporary || s.local || s.last_read != -1) {
            System.out.println("--WRITE r:" + register + " l:" + line + " .. " + s.last_read);
            if(s.local) System.out.println("--  LOCAL");
            if(s.temporary) System.out.println("--  TEMPORARY");
            if(s.written) System.out.println("--  WRITE");
            System.out.println("--  READ_COUNT " + s.read_count);
          }
        }
      }
    }
    //
    List<Declaration> declList = new ArrayList<Declaration>(registers); 
    for(int register = 0; register < registers; register++) {
      String id = "L";
      boolean local = false;
      boolean temporary = false;
      int read = 0;
      int written = 0;
      int start = -1;
      if(register < args) {
        local = true;
        id = "A";
      }
      boolean is_arg = false;
      if(register == args) {
        switch(d.getVersion().varargtype.get()) {
        case ARG:
        case HYBRID:
          if((d.function.vararg & 1) != 0) {
            local = true;
            is_arg = true;
          }
          break;
        case ELLIPSIS:
          break;
        }
      }
      if(!local && !temporary) {
        for(int line = 1; line <= code.length(); line++) {
          RegisterState state = states.get(register, line);
          if(state.local) {
            temporary = false;
            local = true;
          }
          if(state.temporary) {
            start = -1;
            temporary = true;
          }
          if(state.read) {
            written = 0; read++;
          }
          if(state.written) {
            if (start == -1 && !state.temporary) start = line;
            if(written > 0 && read == 0) {
              temporary = false;
              local = true;
            }
            read = 0; written++;
          }
        }
      }
      if (start == -1) start = 0;
      if(!local && !temporary) {
        if(read >= 2 || read == 0 && written != 0) {
          local = true;
        }
      }
      if(local && temporary) {
        //throw new IllegalStateException();
      }
      if(local) {
        String name;
        if(is_arg) {
          name = "arg";
        } else {
          name = id + register + "_" + lc++;
        }
        Declaration decl = new Declaration(name, start, code.length() + d.getVersion().outerblockscopeadjustment.get());
        decl.register = register;
        declList.add(decl);
      }
    }
    //DEBUG
/*
    for(Declaration decl : declList) {
      System.out.println("decl: " + decl.name + " " + decl.begin + " " + decl.end);
    }*/
    return declList.toArray(new Declaration[declList.size()]);
  }
  
  static int lc = 0;
  
  private VariableFinder() {}
  
}
