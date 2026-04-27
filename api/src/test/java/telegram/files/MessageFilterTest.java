package telegram.files;

import cn.hutool.core.util.ReUtil;
import org.apache.commons.jexl3.JexlExpression;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageFilterTest {

    private static List<TdApi.Message> messages;

    @BeforeAll
    static void setup() {
        TdApi.Message msg1 = new TdApi.Message();
        msg1.id = 1;
        msg1.content = new TdApi.MessageText(
                new TdApi.FormattedText("Hello World", null),
                null,
                null
        );

        TdApi.Message msg2 = new TdApi.Message();
        msg2.id = 2;
        msg2.content = new TdApi.MessageText(
                new TdApi.FormattedText("Test Message", null),
                null,
                null
        );

        TdApi.Message msg3 = new TdApi.Message();
        msg3.id = 3;
        msg3.content = new TdApi.MessageText(
                new TdApi.FormattedText("Another Hello", null),
                null,
                null
        );

        messages = Arrays.asList(msg1, msg2, msg3);
    }

    @Test
    void testFilterByTextContains() {
        String expr = "str:contains(content.text.text, 'Hello')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(m -> m.content instanceof TdApi.MessageText &&
                                                   ((TdApi.MessageText) m.content).text.text.contains("Hello")));
    }

    @Test
    void testFilterById() {
        String expr = "id > 1";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(m -> m.id > 1));
    }

    @Test
    void testInvalidExpression() {
        String expr = "invalidFunc(content.text.text)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(0, filtered.size());
    }

    @Test
    void testExpressionCache() {
        String expr = "id > 0";
        JexlExpression expression = MessageFilter.getExpression(expr);
        assertEquals(expression, MessageFilter.getExpression(expr), "Expression should be cached and identical");
    }

    @Test
    void testFilterByRegex() {
        assertTrue(ReUtil.isMatch("Test.*", "Test Message"));
        String expr = "re:isMatch('Test.*', content.text.text)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(1, filtered.size());
        assertInstanceOf(TdApi.MessageText.class, filtered.getFirst().content);
        assertEquals("Test Message", ((TdApi.MessageText) filtered.getFirst().content).text.text);
    }

    @Test
    void testFilterByVideoSize() {
        TdApi.Message videoMsg = new TdApi.Message();
        videoMsg.id = 4;
        TdApi.Video video = new TdApi.Video();
        video.video = new TdApi.File();
        video.video.size = 5000;
        video.video.remote = new TdApi.RemoteFile();
        videoMsg.content = new TdApi.MessageVideo();
        ((TdApi.MessageVideo) videoMsg.content).video = video;
        ((TdApi.MessageVideo) videoMsg.content).caption = new TdApi.FormattedText();
        List<TdApi.Message> videoMessages = List.of(videoMsg);

        String expr = "content.video.video.size > 4000";
        List<TdApi.Message> filtered = MessageFilter.filter(videoMessages, expr);
        assertEquals(1, filtered.size());
        assertEquals(4, filtered.getFirst().id);

        // Test use of 'f' alias for file
        String exprAlias = "f.size() > 4000";
        List<TdApi.Message> filteredAlias = MessageFilter.filter(videoMessages, exprAlias);
        assertEquals(1, filteredAlias.size());
        assertEquals(4, filteredAlias.getFirst().id);
    }

    @Test
    void testSecurityAgainstCodeInjection() {
        String expr = "java.lang.System.exit(0)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(0, filtered.size(), "No messages should be returned for malicious expressions");
    }

    // Security regression tests for issue jarvis2f/telegram-files#130
    // These test that the JEXL sandbox properly blocks RCE vectors

    @Test
    void testSecurityBlocksRuntimeUtil() {
        // PoC from upstream issue #130: RuntimeUtil allows arbitrary command execution
        String expr = "new('cn.hutool.core.util.RuntimeUtil').execForStr('id')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);
        assertEquals(0, filtered.size(), "RuntimeUtil must be blocked by JEXL sandbox");
    }

    @Test
    void testSecurityBlocksProcessBuilder() {
        // ProcessBuilder is another RCE vector
        String expr = "new('java.lang.ProcessBuilder')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);
        assertEquals(0, filtered.size(), "ProcessBuilder must be blocked by JEXL sandbox");
    }

    @Test
    void testSecurityBlocksRuntime() {
        // Runtime via class reflection
        String expr = "''.class.forName('java.lang.Runtime')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);
        assertEquals(0, filtered.size(), "Runtime access must be blocked by JEXL sandbox");
    }

    @Test
    void testSecurityBlocksSystemExit() {
        // System.exit via new() constructor
        String expr = "new('java.lang.System').exit(0)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);
        assertEquals(0, filtered.size(), "System instantiation must be blocked");
    }

    @Test
    void testInputValidationBlocksConstructorCalls() {
        // Defense-in-depth: input validation should catch new() before JEXL sees it
        String expr = "new('anything')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);
        assertEquals(0, filtered.size(), "Expressions with new() must be rejected by input validation");
    }
}
