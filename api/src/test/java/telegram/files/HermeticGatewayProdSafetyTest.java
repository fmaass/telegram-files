package telegram.files;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.http.HermeticGateway;

import java.util.List;

/**
 * Prod-safety of the Phase-5 hermetic test gateway: it MUST be unreachable/absent under
 * {@code APP_ENV=prod}. The pure decision {@link HermeticGateway#enabledFor(boolean, String)} is
 * asserted directly for the prod case (so the static-final {@code Config.APP_ENV} need not be mutated),
 * and {@link HermeticGateway#mount} is proven to add ZERO routes when disabled — the injector surface
 * is genuinely absent from a prod build, not merely guarded per-request.
 */
class HermeticGatewayProdSafetyTest {

    static Vertx vertx;

    @BeforeAll
    static void setUp() {
        new java.io.File(Config.LOG_PATH).mkdirs();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("prod: the gateway is hard-off even with HERMETIC_GATEWAY=1 (prod overrides the flag)")
    void hardOffUnderProd() {
        // isProd=true must force-disable regardless of the opt-in flag value.
        Assertions.assertFalse(HermeticGateway.enabledFor(true, "1"),
                "prod build must NEVER enable the gateway, even with the flag set");
        Assertions.assertFalse(HermeticGateway.enabledFor(true, "true"));
        Assertions.assertFalse(HermeticGateway.enabledFor(true, null));
    }

    @Test
    @DisplayName("non-prod: gateway enabled only when the opt-in flag is set")
    void nonProdRequiresFlag() {
        Assertions.assertFalse(HermeticGateway.enabledFor(false, null),
                "non-prod without the flag stays disabled");
        Assertions.assertFalse(HermeticGateway.enabledFor(false, "0"));
        Assertions.assertTrue(HermeticGateway.enabledFor(false, "1"),
                "non-prod WITH the flag enables the gateway");
        Assertions.assertTrue(HermeticGateway.enabledFor(false, "true"));
    }

    @Test
    @DisplayName("mount adds NO routes when the gateway is disabled (surface absent)")
    void mountAddsNoRoutesWhenDisabled() {
        // In the test env APP_ENV=dev and HERMETIC_GATEWAY is unset -> disabled.
        Assertions.assertFalse(HermeticGateway.isEnabled(),
                "default test env (no flag) must have the gateway disabled");

        Router router = Router.router(vertx);
        int before = router.getRoutes().size();
        HermeticGateway.mount(router, vertx);
        List<Route> after = router.getRoutes();
        Assertions.assertEquals(before, after.size(),
                "a disabled gateway must add ZERO routes — the injector surface must be absent");
        // No gateway path may exist.
        Assertions.assertTrue(after.stream().noneMatch(r ->
                        r.getPath() != null && r.getPath().contains("/test/gateway")),
                "no /test/gateway route may be present when disabled");
    }
}
