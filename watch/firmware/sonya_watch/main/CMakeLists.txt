idf_component_register(
    SRCS "app_main.c" "status_ui.c" "status_screen.c"
    INCLUDE_DIRS "."
    REQUIRES
        nvs_flash
        bt
        driver
        esp_lcd
        esp_lcd_sh8601
        esp_timer
        freertos
        heap
        sonya_ble
        audio_cap
        wake
        protocol
        rec_store
        pull_stream
)
