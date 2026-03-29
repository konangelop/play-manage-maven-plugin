package controllers;

import javax.inject.Inject;
import javax.inject.Provider;
import play.mvc.Controller;
import play.mvc.Result;
import com.typesafe.config.Config;

public class HealthController extends Controller {

    private final Provider<Config> configProvider;

    @Inject
    public HealthController(Provider<Config> configProvider) {
        this.configProvider = configProvider;
    }

    public Result health() {
        return ok("healthy-v2");
    }

    public Result status() {
        return ok(views.html.status.render());
    }
}
