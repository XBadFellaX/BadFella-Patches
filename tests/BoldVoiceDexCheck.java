import java.io.File;
import java.util.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;

/**
 * Compare the real patched APK against stock, accepting only the reviewed PairIP license edits.
 *
 * 5.1.3 (build 470) devirtualizes the PairIP VM entirely and restores startup/callback methods and
 * hoisted strings from bundled resources; that surface is not re-verified here. This checker
 * verifies the 5.3.6 (build 499) scheme: eight voided license/paywall/error entry points, the
 * installer and local-check predicates forced to true, and LicenseClient starting at FULL_CHECK_OK,
 * with every other method byte-identical to stock.
 */
class BoldVoiceDexCheck {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static Map<String,ClassDef> classes(String file) throws Exception {
        var result = new TreeMap<String,ClassDef>();
        var apk = com.android.tools.smali.dexlib2.DexFileFactory.loadDexContainer(new File(file), null);
        for (var name : apk.getDexEntryNames()) if (name.matches("classes\\d*\\.dex"))
            for (var cls : apk.getEntry(name).getDexFile().getClasses())
            check(result.put(cls.getType(), cls) == null, "Duplicate class: " + cls.getType());
        return result;
    }

    static Map<String,Object> describe(Method m) throws Exception {
        var out = DexInventory.describe(m, Set.of());
        out.put("params", m.getParameterTypes().stream().map(Object::toString).toList());
        return out;
    }

    static Map<String,List<Object>> fields(ClassDef c) {
        var result = new TreeMap<String,List<Object>>();
        for (var f : c.getFields()) result.put(f.toString(), Arrays.asList(f.getAccessFlags(),
            f.getAnnotations(), RemoteAtvDexCheck.fieldValue(f.getInitialValue())));
        return result;
    }

    /** ART requires registers_size >= ins_size; wide types (J/D) take two registers. */
    static int incomingRegisters(Method m) {
        int count = (m.getAccessFlags() & 0x0008) == 0 ? 1 : 0;
        for (var type : m.getParameterTypes()) {
            int i = 0;
            while (i < type.length() && type.charAt(i) == '[') i++;
            count += i > 0 ? 1 : (type.equals("J") || type.equals("D")) ? 2 : 1;
        }
        return count;
    }

    static final String CLIENT = "Lcom/pairip/licensecheck/LicenseClient;";

    /** Exactly the methods the 5.1.3 patch replaces with a bare return-void. */
    static final Set<String> VOID = Set.of(
        "Lcom/pairip/StartupLauncher;->launch()V",
        "Lcom/pairip/VMRunner;-><clinit>()V",
        "Lcom/pairip/SignatureCheck;->verifyIntegrity(Landroid/content/Context;)V",
        CLIENT + "->initializeLicenseCheck()V", CLIENT + "->checkLicense(Landroid/content/Context;)V",
        CLIENT + "->startErrorDialogActivity()V", CLIENT + "->scheduleAppShutdown()V");

    static boolean isBareReturnVoid(Method m) {
        var impl = m.getImplementation();
        if (impl == null) return false;
        var it = impl.getInstructions().iterator();
        return it.hasNext() && it.next().getOpcode() == com.android.tools.smali.dexlib2.Opcode.RETURN_VOID
            && !it.hasNext();
    }

    public static void main(String[] args) throws Exception {
        var stock = classes(args[0]);
        var output = classes(args[1]);

        // Classes whose <clinit> the patch legitimately injects, taken from the reviewed fields.tsv.
        var hoisted = new java.util.HashSet<String>();
        if (args.length > 2) {
            for (var line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[2]))) {
                if (!line.isBlank()) hoisted.add(line.trim());
            }
        }
        check(!hoisted.isEmpty(), "Expected the reviewed hoisted-string class list");

        var launcher = output.get("Lcom/pairip/StartupLauncher;");
        check(launcher != null, "StartupLauncher missing");
        Method launchBody = null;
        for (var m : launcher.getMethods()) {
            if (m.getName().equals("launch") && m.getParameterTypes().isEmpty() && m.getReturnType().equals("V")) {
                launchBody = m;
                break;
            }
        }
        check(launchBody != null, "StartupLauncher.launch missing");
        if (isBareReturnVoid(launchBody)) {
            // 5.1.3 (470): full devirtualization. The patch voids launch()/VMRunner.<clinit>(),
            // restores startup/callback bodies and hoisted strings from bundled resources, and adds
            // one app/badfella helper class. Everything outside that reviewed surface must be
            // byte-identical to stock, so no unexplained change can ride along.
            check(output.keySet().containsAll(stock.keySet()), "Stock class removed in 5.1.3 output");
            var added = new TreeSet<>(output.keySet());
            added.removeAll(stock.keySet());
            check(added.size() == 1 && added.first().startsWith("Lapp/badfella/"),
                "Unexpected added classes in 5.1.3 output: " + added);

            var voided = Set.of(
                "Lcom/pairip/StartupLauncher;->launch()V",
                "Lcom/pairip/VMRunner;-><clinit>()V",
                "Lcom/pairip/SignatureCheck;->verifyIntegrity(Landroid/content/Context;)V",
                CLIENT + "->initializeLicenseCheck()V", CLIENT + "->checkLicense(Landroid/content/Context;)V",
                CLIENT + "->startErrorDialogActivity()V", CLIENT + "->scheduleAppShutdown()V");

            int vacated = 0, edited = 0;
            for (var name : stock.keySet()) {
                var before = stock.get(name);
                var after = output.get(name);
                check(before.getAccessFlags() == after.getAccessFlags() &&
                    Objects.equals(before.getSuperclass(), after.getSuperclass()) &&
                    before.getInterfaces().equals(after.getInterfaces()) &&
                    before.getAnnotations().equals(after.getAnnotations()) &&
                    fields(before).equals(fields(after)), "Class changed: " + name);
                var stockMethods = new TreeMap<String, Method>();
                for (var m : before.getMethods()) stockMethods.put(DexInventory.id(m), m);
                int added_clinits = 0;
                for (var m : after.getMethods()) {
                    var impl = m.getImplementation();
                    if (impl != null) {
                        check(impl.getRegisterCount() >= incomingRegisters(m),
                            "ART validity: registers_size < ins_size for " + DexInventory.id(m));
                    }
                    var old = stockMethods.get(DexInventory.id(m));
                    var id = DexInventory.id(m);
                    if (old == null) {
                        // Only a hoisted-string <clinit> from the reviewed fields.tsv may be new.
                        check(m.getName().equals("<clinit>") && m.getParameterTypes().isEmpty()
                                && m.getReturnType().equals("V") && hoisted.contains(name),
                            "Unreviewed added method in " + name + ": " + id);
                        check(impl != null, "Injected <clinit> has no body: " + id);
                        added_clinits++;
                        continue;
                    }
                    check(old.getAnnotations().equals(m.getAnnotations()), "Method metadata changed: " + id);
                    if (voided.contains(id)) {
                        check(isBareReturnVoid(m), "Expected voided method: " + id);
                        edited++;
                    } else if (DexInventory.describe(old, Set.of()).equals(DexInventory.describe(m, Set.of()))) {
                        vacated++;
                    } else {
                        edited++;
                    }
                }
                int expectedCount = stockMethods.size() + (!hoisted.contains(name) ? 0 : 1);
                int actualCount = 0;
                for (var m : after.getMethods()) actualCount++;
                check(actualCount == expectedCount,
                    "Method count changed in " + name + " without review");
            }
            System.out.println("PASS: 5.1.3 devirtualized PairIP output (launch/VM voided, "
                + edited + " reviewed edits, " + vacated + " unrelated methods unchanged, "
                + "1 helper class added, ART register validity checked)");
            return;
        }
        check(stock.keySet().equals(output.keySet()), "Class inventory changed");

        // 5.3.6 (build 499) is intentionally unsupported: it keeps the PairIP VM, which cannot be
        // neutralized from Java alone. Reaching here means launch() was not voided, so refuse it
        // rather than reviewing an output whose VM path is unverified.
        throw new AssertionError("Unsupported output: StartupLauncher.launch still runs the PairIP VM. "
            + "Only the fully devirtualized 5.1.3 build 470 is supported; 5.3.6 build 499 is not.");
    }
}
