package telegram.files;

import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityHardeningTest {

    private static List<TdApi.Message> messages;

    @BeforeAll
    static void setup() {
        TdApi.Message msg = new TdApi.Message();
        msg.id = 1;
        msg.content = new TdApi.MessageText(
                new TdApi.FormattedText("Hello World", null), null, null);
        messages = List.of(msg);
    }

    @Test
    @DisplayName("JEXL: str namespace works (kept)")
    void test_jexl_str_namespace_works() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "str:contains(content.text.text, 'Hello')");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("JEXL: date namespace works (kept)")
    void test_jexl_date_namespace_works() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "date:current() != null");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("JEXL: num namespace works (kept)")
    void test_jexl_num_namespace_works() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "num:isNumber('123')");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("JEXL: re namespace works (kept)")
    void test_jexl_re_namespace_works() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "re:isMatch('Hello.*', content.text.text)");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("JEXL: class namespace rejected (removed — RCE risk)")
    void test_jexl_class_namespace_rejected() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "class:getClassName(content, false) != null");
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("JEXL: net namespace rejected (removed — SSRF risk)")
    void test_jexl_net_namespace_rejected() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "net:isInnerIP('127.0.0.1')");
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("JEXL: zip namespace rejected (removed)")
    void test_jexl_zip_namespace_rejected() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "zip:gzip('test', 'UTF-8') != null");
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("JEXL: id namespace rejected (removed)")
    void test_jexl_id_namespace_rejected() {
        List<TdApi.Message> result = MessageFilter.filter(messages,
                "id:randomUUID() != null");
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("TDLib API allows whitelisted method")
    void test_telegram_api_allows_whitelisted_method() {
        assertTrue(HttpVerticle.ALLOWED_TDLIB_METHODS.contains("GetMessageThread"));
        assertTrue(HttpVerticle.ALLOWED_TDLIB_METHODS.contains("SetAuthenticationPhoneNumber"));
    }

    @Test
    @DisplayName("TDLib API rejects unlisted method")
    void test_telegram_api_rejects_unlisted_method() {
        assertFalse(HttpVerticle.ALLOWED_TDLIB_METHODS.contains("DeleteAccount"));
        assertFalse(HttpVerticle.ALLOWED_TDLIB_METHODS.contains("SendMessage"));
        assertFalse(HttpVerticle.ALLOWED_TDLIB_METHODS.contains("DestroySession"));
    }

    @Test
    @DisplayName("SQL: sort rejects injection attempt")
    void test_getFiles_rejects_sql_in_sort() {
        String maliciousSort = "id); DROP TABLE--";
        assertFalse(FileRepositoryImplTestHelper.isValidSort(maliciousSort));
    }

    @Test
    @DisplayName("SQL: null order does not NPE")
    void test_getFiles_null_order_no_npe() {
        assertDoesNotThrow(() -> FileRepositoryImplTestHelper.isValidSort("id"));
    }
}
