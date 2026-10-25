package vpmac;
import java.nio.file.*; import java.util.*; import java.util.zip.*; import java.net.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
public class Harness {
  public static void main(String[] a) throws Exception {
    Path jar=Paths.get(a[0]), out=Paths.get(a[1]); Rules.load(); Transformer t=new Transformer();
    List<String> changed=new ArrayList<>(); int total=0; Map<String,byte[]> res=new HashMap<>();
    try(ZipFile z=new ZipFile(jar.toFile())){ for(Enumeration<? extends ZipEntry> e=z.entries();e.hasMoreElements();){ ZipEntry en=e.nextElement(); if(!en.getName().endsWith(".class"))continue; total++;
      byte[] b=z.getInputStream(en).readAllBytes(); String n=en.getName().substring(0,en.getName().length()-6);
      byte[] r=t.transform(null,n,null,null,b); Path p=out.resolve(en.getName()); Files.createDirectories(p.getParent()); Files.write(p, r==null?b:r); if(r!=null){changed.add(n); res.put(n,r);} } }
    System.out.println("clases: "+total+"  reescritas: "+changed.size());
    URL[] cp=new URL[a.length-1]; cp[0]=out.toUri().toURL(); for(int i=2;i<a.length;i++) cp[i-1]=Paths.get(a[i]).toUri().toURL();
    URLClassLoader cl=new URLClassLoader(cp, ClassLoader.getPlatformClassLoader());
    // 1) cada llamada a imitaciones/vpmac apunta a un metodo static existente con la firma exacta
    Set<String> bad=new TreeSet<>(); int checked=0;
    for(Map.Entry<String,byte[]> e: res.entrySet()){ ClassNode cn=new ClassNode(); new ClassReader(e.getValue()).accept(cn,0);
      for(MethodNode m: cn.methods) for(AbstractInsnNode in: m.instructions) if(in instanceof MethodInsnNode mi && (mi.owner.startsWith("viewpoint/mac41/")||mi.owner.startsWith("vpmac/"))){ checked++;
        if(!exists(cl, mi.owner, mi.name, mi.desc, mi.getOpcode()==Opcodes.INVOKESTATIC)) bad.add(mi.owner+"."+mi.name+mi.desc+"  (en "+e.getKey()+")"); } }
    System.out.println("llamadas nuevas revisadas: "+checked+"  sin destino: "+bad.size()); bad.forEach(s->System.out.println("  SIN DESTINO "+s));
    // 2) verificacion de bytecode: linkear (se verifica antes de inicializar)
    int ok=0, vf=0, other=0;
    for(String n: changed){ try{ Class.forName(n.replace('/','.'),true,cl); ok++; }
      catch(VerifyError|ClassFormatError|IncompatibleClassChangeError e){ vf++; System.out.println("  VERIFY "+n+": "+e); }
      catch(Throwable e){ other++; /* inicializacion sin contexto GL/juego: no es error de bytecode */ } }
    System.out.println("verificadas e inicializadas: "+ok+"  verificadas (init fallo sin juego): "+other+"  ERRORES DE BYTECODE: "+vf);
  }
  static boolean exists(ClassLoader cl, String owner, String name, String desc, boolean isStatic){
    try(var in=cl.getResourceAsStream(owner+".class")){ if(in==null) return false; ClassNode cn=new ClassNode(); new ClassReader(in.readAllBytes()).accept(cn,0);
      for(MethodNode m: cn.methods) if(m.name.equals(name)&&m.desc.equals(desc)&&((m.access&Opcodes.ACC_STATIC)!=0)==isStatic) return true; return false; }catch(Exception e){return false;} }
}
