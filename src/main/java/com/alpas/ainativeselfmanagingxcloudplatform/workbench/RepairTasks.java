package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Public smoke tasks. Feedback and evaluation sets have disjoint task identifiers. */
@Component
public class RepairTasks {
    private static Task task(String id, String name, String method, String assertions, String instruction) {
        return new Task(id,name,instruction,Map.of("src/main/java/Rules.java", "public class Rules { " + method + " }\n",
                "src/test/java/RulesTest.java", "public class RulesTest { public static void main(String[] args) { " + assertions + " System.out.println(\"All regression checks passed\"); } }\n"),"RulesTest");
    }
    public List<Task> examples() {
        return List.of(
            task("feedback-discount","Discount calculation","static int price(int amount) { if (amount < 0) throw new IllegalArgumentException(); return amount + 5; }",
                "if (Rules.price(12) != 7 || Rules.price(5) != 0 || Rules.price(20) != 15) throw new AssertionError(\"Subtract the discount\"); boolean rejected=false; try { Rules.price(-1); } catch(IllegalArgumentException e) { rejected=true; } if(!rejected) throw new AssertionError(\"Negative input must be rejected\");",
                "Fix the discount calculation while preserving rejection of negative amounts. Run the existing tests without editing them."),
            task("feedback-boundary","Inclusive eligibility boundary","static boolean eligible(int age) { return age > 18; }",
                "if (!Rules.eligible(18) || !Rules.eligible(30) || Rules.eligible(17)) throw new AssertionError(\"Eligibility starts at 18\");",
                "Correct the eligibility boundary. Keep the test sources unchanged."));
    }
    public Task require(String id) { return examples().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new PlatformException(404,"TASK_NOT_FOUND","Choose a catalog task or import a bounded source snapshot.")); }
    List<Task> holdout() {
        return List.of(
            task("holdout-fee","Fee deduction","static int net(int amount) { if(amount < 0) throw new IllegalArgumentException(); return amount + 2; }",
                "if(Rules.net(10)!=8 || Rules.net(2)!=0) throw new AssertionError(\"Deduct fee\"); boolean rejected=false; try { Rules.net(-3); } catch(IllegalArgumentException e) { rejected=true; } if(!rejected) throw new AssertionError();",
                "Correct net amount after fee deduction."),
            task("holdout-hours","Hours conversion","static int minutes(int hours) { if(hours < 0) throw new IllegalArgumentException(); return hours / 60; }",
                "if(Rules.minutes(2)!=120 || Rules.minutes(0)!=0 || Rules.minutes(3)!=180) throw new AssertionError(\"Convert hours to minutes\"); boolean rejected=false; try { Rules.minutes(-1); } catch(IllegalArgumentException e) { rejected=true; } if(!rejected) throw new AssertionError();",
                "Correct conversion from hours to minutes."),
            task("holdout-boundary","Capacity boundary","static boolean fits(int count) { return count < 8; }",
                "if(!Rules.fits(8) || !Rules.fits(7) || Rules.fits(9)) throw new AssertionError(\"Inclusive capacity\");",
                "Capacity includes the boundary value."));
    }
}
