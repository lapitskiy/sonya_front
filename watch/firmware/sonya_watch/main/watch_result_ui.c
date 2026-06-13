#include "watch_result_ui.h"

#include "esp_log.h"
#include "status_ui.h"

static const char *TAG = "watch_result_ui";

void watch_result_ui_show_backend_result(bool ok)
{
    ESP_LOGI(TAG, "backend result ui: %s", ok ? "OK" : "ERR");
    if (ok) {
        status_ui_show_ok(2200);
    } else {
        status_ui_show_message("ERR", 2200);
    }
}
