import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "r") as f:
    content = f.read()

# The user is likely using an older version of litertlm-android that doesn't support the boolean useXnnpack parameter.
# We will revert to only passing the thread count to Backend.CPU.
# However, we still want to implement the analysis.
# If we can't disable XNNPACK via the constructor, we can't do it this way.

# Let's check the imports to be sure if we can find the version or just fix the call.
# Since it failed with "Too many arguments", we must remove the second argument.

content = content.replace("Backend.CPU(dynamicThreads, !xnnpackBanned)", "Backend.CPU(dynamicThreads)")

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/engine/LiteRTInferenceEngine.kt", "w") as f:
    f.write(content)
