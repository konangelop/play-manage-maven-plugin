package controllers;

import play.mvc.Controller;
import play.mvc.Result;

public class HealthController extends Controller {

    public Result health() {
        return ok("healthy-v2");
    }

    public Result status() {
        return ok(views.html.status.render());
    }
}
