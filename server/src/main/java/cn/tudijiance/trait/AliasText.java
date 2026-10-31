package cn.tudijiance.trait;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 表头的规范化写法，用来比较表头是不是同一个：全角转半角（NFKC）、去掉所有空白、英文字母小写。
 * 例如"株高 （CM）"和"株高(cm)"规范化后相同。客户端 importGuess.ts 里有同样的规则。
 */
public final class AliasText {

    public static final int MAX_LENGTH = 64;

    private AliasText() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
