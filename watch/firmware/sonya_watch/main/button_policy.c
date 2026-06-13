#include "button_policy.h"

#include "sdkconfig.h"

button_policy_decision_t button_policy_decide(bool triggered_by_button, bool link_connected)
{
#if defined(CONFIG_WAKE_MODE_BUTTON)
    if (triggered_by_button && !link_connected) {
        return BUTTON_POLICY_IGNORE_NO_LINK;
    }
#else
    (void)triggered_by_button;
#endif
    (void)link_connected;
    return BUTTON_POLICY_RECORD;
}

const char *button_policy_decision_name(button_policy_decision_t decision)
{
    switch (decision) {
    case BUTTON_POLICY_RECORD: return "record";
    case BUTTON_POLICY_IGNORE_NO_LINK: return "ignore_no_link";
    default: return "unknown";
    }
}
