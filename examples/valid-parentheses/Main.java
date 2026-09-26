import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        String text = new Scanner(System.in).next();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            depth += text.charAt(i) == '(' ? 1 : -1;
            if (depth < 0) {
                System.out.println("NO");
                return;
            }
        }
        System.out.println(depth == 0 ? "YES" : "NO");
    }
}
