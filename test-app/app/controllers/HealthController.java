package controllers;

import javax.inject.Inject;
import play.mvc.Controller;
import play.mvc.Result;
import com.typesafe.config.Config;

public class HealthController extends Controller {

    private final Config config;

    @Inject
    public HealthController(Config config) {
        this.config = config;
    }

    public Result health() {
        return ok("healthy-v2");
    }

    public Result status() {
        return ok(views.html.status.render());
    }
}
