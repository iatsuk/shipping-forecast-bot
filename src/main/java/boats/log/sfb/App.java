package boats.log.sfb;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class App {

    @SuppressWarnings("unused")
    public static void main(String[] args) throws InterruptedException {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            Thread.currentThread().join();
        }
    }
}
