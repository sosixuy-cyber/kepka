from __future__ import annotations

import os

os.environ.setdefault("LOCAL_DEV_START_BALANCE", "10")

from werkzeug.wrappers.response import Response as WerkzeugResponse

_original_set_cookie = WerkzeugResponse.set_cookie


def _set_cookie_compat(self, *args, **kwargs):
    kwargs.pop("partitioned", None)
    return _original_set_cookie(self, *args, **kwargs)


WerkzeugResponse.set_cookie = _set_cookie_compat

from server import app

app.config.update(
    DEBUG=True,
    SESSION_COOKIE_PARTITIONED=False,
    SESSION_COOKIE_SECURE=False,
    SESSION_COOKIE_SAMESITE="Lax",
    PREFERRED_URL_SCHEME="http",
)


if __name__ == "__main__":
    app.run(
        host="127.0.0.1",
        port=int(os.getenv("PORT", "8011")),
        debug=True,
        use_reloader=False,
    )
