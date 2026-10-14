import com.yuuuno224.bilimusic.net.WbiSigner;

import java.util.Map;
import java.util.TreeMap;

/**
 * WBI 签名验证探针。
 *
 * <p>为什么需要它：WBI 签名的三步顺序错了，服务端只会回一句"签名校验失败"，
 * 不会告诉你错在哪。所以这里用一组<b>社区验证过的已知值</b>做对照：
 * 只要 mixinKey 和签名结果与预期一致，算法就是对的。
 *
 * <p>编译与运行（不需要 Android SDK，WbiSigner 与 UrlCodec 都是纯 Java）：
 * <pre>
 * javac -d out -sourcepath app/src/main/java tools/api-probe/_04_WbiVerify.java
 * java -cp out _04_WbiVerify
 * </pre>
 * 期望输出里 MATCH 与 CHECK 都是 true。
 *
 * <p>buvid3 与时间戳相关的那几行输出每次运行都会变（wts 是当前时间），属正常现象。
 */
public class _04_WbiVerify {

    /** 取自 B 站 nav 接口的真实 imgKey（脱敏示例值，与 subKey 配套）。 */
    private static final String IMG_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";

    /** 上面两个 key 混淆后<b>应该</b>得到的 mixinKey（社区验证过的已知值）。 */
    private static final String EXPECTED_MIXIN_KEY = "ea1db124af3c7062474693fa704f4ff8";

    public static void main(String[] args) {
        System.out.println("=== 1. mixinKey 混淆 ===");
        String mixin = WbiSigner.mixinKey(IMG_KEY, SUB_KEY);
        System.out.println("imgKey + subKey 长度 = " + (IMG_KEY + SUB_KEY).length() + "（应为 64）");
        System.out.println("mixinKey  = " + mixin);
        System.out.println("expected  = " + EXPECTED_MIXIN_KEY);
        System.out.println("mixinKey 长度 = " + mixin.length() + "（应为 32）");
        boolean match = EXPECTED_MIXIN_KEY.equals(mixin);
        System.out.println("MATCH = " + match);

        System.out.println();
        System.out.println("=== 2. 签名 query 结构 ===");
        Map<String, String> params = new TreeMap<>();
        params.put("keyword", "周杰伦 晴天");
        params.put("search_type", "video");
        params.put("page", "1");

        String query = WbiSigner.sign(params, mixin);
        System.out.println("query = " + query);
        System.out.println("包含 &wts=  = " + query.contains("&wts="));
        System.out.println("包含 &w_rid= = " + query.contains("&w_rid="));
        System.out.println("空格编成 %20  = " + query.contains("%20"));
        System.out.println("空格没编成 +  = " + !query.contains("+"));

        // 签名串必须按 key 的字典序排列：keyword < page < search_type < wts
        int keywordAt = query.indexOf("keyword=");
        int pageAt = query.indexOf("page=");
        int typeAt = query.indexOf("search_type=");
        int wtsAt = query.indexOf("wts=");
        boolean sorted = keywordAt < pageAt && pageAt < typeAt && typeAt < wtsAt;
        System.out.println("参数按键字典序 = " + sorted);

        boolean check = match && query.contains("&w_rid=") && query.contains("&wts=")
                && query.contains("%20") && !query.contains("+") && sorted;
        System.out.println();
        System.out.println("CHECK = " + check);

        if (!check) {
            System.exit(1);
        }
    }
}
