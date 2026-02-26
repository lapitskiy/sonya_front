idf_component_register(
    SRCS "audio_cap.c"
    INCLUDE_DIRS "include"
    REQUIRES driver freertos esp_timer sonya_board
    PRIV_REQUIRES espressif__esp_codec_dev
)
