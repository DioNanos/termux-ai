import java.lang.reflect.Method;

/** Minimal JUnit runner: runs every @Test method of the given classes. */
public class RunTests {
    public static void main(String[] args) throws Exception {
        int run = 0, failed = 0;
        for (String cls : args) {
            Class<?> c = Class.forName(cls);
            Object instance = c.getDeclaredConstructor().newInstance();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(org.junit.Test.class)) {
                    run++;
                    try {
                        m.invoke(instance);
                        System.out.println("PASS " + cls + "." + m.getName());
                    } catch (Exception e) {
                        failed++;
                        Throwable t = e.getCause() != null ? e.getCause() : e;
                        System.out.println("FAIL " + cls + "." + m.getName() + ": " + t);
                    }
                }
            }
        }
        System.out.println("tests=" + run + " failures=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
