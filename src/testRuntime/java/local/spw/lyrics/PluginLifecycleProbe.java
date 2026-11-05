package local.spw.lyrics;

import com.xuncorp.spw.workshop.api.*;
import com.xuncorp.spw.workshop.api.config.*;
import local.spw.host.HostResolver;
import org.pf4j.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.classfile.instruction.*;

/** Load the delivered package through the real manager and registrar, with no access to the user's profile. */
public class PluginLifecycleProbe {
    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(() -> { try { Thread.sleep(180000); } catch (InterruptedException ignored) {} System.exit(4); });
        watchdog.setDaemon(true); watchdog.start();
        Path profile = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Path.of(System.getenv("APPDATA")).toAbsolutePath().normalize().equals(profile))
            throw new IllegalStateException("APPDATA must point to the isolated test profile");
        var r = new HostResolver(WorkshopApi.class);
        Class<?> nativeConfig = r.type("com.xuncorp.pisces.PiscesConfig");
        var libraryNames = Set.of("bass.dll","bass_fx.dll","bassmix.dll","basswasapi.dll","bassasio.dll","basscd.dll");
        var configured = new HashSet<String>();
        String resource = null;
        // Reuse only the launcher's six native-path setters; do not execute its global UI/bootstrap side effects.
        var bootstrap = r.function(r.type("com.xuncorp.spw.desktop.SpwLauncher"), "onAppCreated");
        for (var instruction : r.code(bootstrap).code().orElseThrow()) {
            if (instruction instanceof ConstantInstruction c && c.constantValue() instanceof String s && libraryNames.contains(s)) resource=s;
            if (resource!=null && instruction instanceof InvokeInstruction call &&
                    call.owner().asInternalName().equals(nativeConfig.getName().replace('.','/')) && call.type().equalsString("(Ljava/lang/String;)V")) {
                Path file = Path.of(args[2]).resolve(resource).toAbsolutePath();
                if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing test audio runtime: "+file);
                Method setter = nativeConfig.getDeclaredMethod(call.name().stringValue(),String.class);
                setter.setAccessible(true); setter.invoke(null,file.toString());
                configured.add(resource); resource=null;
                if(configured.size()==6) break;
            }
        }
        if(!configured.equals(libraryNames)) throw new AssertionError("Incomplete isolated audio runtime setup");
        Class<?> paths = r.type("com.xuncorp.spw.core.util.PathUtil");
        var appDir = r.getter(paths, "appDir");
        Path app = ((java.io.File)appDir.invoke(r.receiver(appDir))).toPath();
        if (!app.toAbsolutePath().normalize().startsWith(profile)) throw new IllegalStateException("Host profile escaped isolation");
        Class<?> apiType = r.type("com.xuncorp.voxzen.workshop.WorkshopApiInstance");
        WorkshopApi nativeApi = (WorkshopApi)r.singleton(apiType);
        var manager = (AbstractPluginManager)r.field(apiType, "pluginManager").get(null);
        Path config = app.resolve("workshop/data/local.spw.lyrics/config.json"); Files.createDirectories(config.getParent());
        var values = new ConcurrentHashMap<String,Object>();
        ConfigHelper helper = new ConfigHelper() {
            @SuppressWarnings("unchecked") public <T> T get(String key,T fallback) { return (T)values.getOrDefault(key,fallback); }
            public void set(String key,Object value) { values.put(key,value); }
            public boolean save() { return true; }
            public boolean reload() { return true; }
            public Path getConfigPath() { return config; }
        };
        ConfigManager configManager = proxy(ConfigManager.class,(p,m,a)->m.getName().equals("getConfig")?helper:null);
        WorkshopApi.Manager apiManager = proxy(WorkshopApi.Manager.class,(p,m,a)->switch(m.getName()) {
            case "createConfigManager" -> configManager;
            case "isPermissionGranted" -> true;
            default -> throw new UnsupportedOperationException(m.toString());
        });
        WorkshopApi.Playback playback = proxy(WorkshopApi.Playback.class,(p,m,a)->null);
        WorkshopApi.Library library = proxy(WorkshopApi.Library.class,(p,m,a)->CompletableFuture.completedFuture(List.of()));
        WorkshopApi.Ui ui = proxy(WorkshopApi.Ui.class,(p,m,a)-> { throw new AssertionError("Unexpected startup toast: "+Arrays.toString(a)); });
        WorkshopApi.Companion.setInstance(proxy(WorkshopApi.class,(p,m,a)->switch(m.getName()) {
            case "getManager" -> apiManager;
            case "getPlayback" -> playback;
            case "getLibrary" -> library;
            case "getUi" -> ui;
            case "getHookRegistrar" -> nativeApi.getHookRegistrar();
            default -> throw new UnsupportedOperationException(m.toString());
        }));
        var importPackage = r.function(apiType,"importPlugin",m->m.getParameterCount()==1 && m.getParameterTypes()[0]==java.io.File.class);
        var updatePackage = r.function(apiType,"updatePlugin",m->m.getParameterCount()==2 && m.getParameterTypes()[0]==String.class && m.getParameterTypes()[1]==java.io.File.class);
        var permissions = r.function(manager.getClass(),"confirmPermissions",m->m.getParameterCount()==3 && m.getParameterTypes()[0]==String.class);
        var requested = Set.of("library-read","library-write","class-transform");
        var granted = EnumSet.of(PluginPermission.LIBRARY_READ,PluginPermission.LIBRARY_WRITE,PluginPermission.CLASS_TRANSFORM);
        Path packageFile = Path.of(args[1]).toAbsolutePath();
        importPackage.invoke(nativeApi,packageFile.toFile());
        ClassLoader previous = null;
        for(int cycle=0;cycle<3;cycle++) {
            permissions.invoke(manager,"local.spw.lyrics",granted,requested);
            manager.enablePlugin("local.spw.lyrics");
            if(manager.startPlugin("local.spw.lyrics")!=PluginState.STARTED) throw new AssertionError("Plugin did not start");
            Object plugin=manager.getPlugin("local.spw.lyrics").getPlugin();
            ClassLoader loader=plugin.getClass().getClassLoader();
            if(!(loader instanceof PluginClassLoader) || loader==previous) throw new AssertionError("Package must load in a fresh plugin classloader");
            previous=loader;
            Field hooks=plugin.getClass().getDeclaredField("hooks"); hooks.setAccessible(true);
            if(((List<?>)hooks.get(plugin)).size()!=6) throw new AssertionError("Not all six hooks registered");
            if(!((androidx.compose.runtime.MutableState<?>)plugin.getClass().getMethod("getEnabled").invoke(plugin)).getValue().equals(true))
                throw new AssertionError("Plugin state is disabled");
            System.out.println("PASS: delivered lyrics package starts with all six native hooks cycle="+cycle);
            updatePackage.invoke(nativeApi,"local.spw.lyrics",packageFile.toFile());
            if(!((List<?>)hooks.get(plugin)).isEmpty()) throw new AssertionError("Old classloader retained registered hooks");
            System.gc();
            System.out.println("PASS: enabled-package update cleans up all prior lyrics hooks cycle="+cycle);
        }
        manager.stopPlugins();
        System.out.println("PASS: actual lyrics package import/start/update/stop, isolated profile and permissions");
        System.exit(0);
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
}
