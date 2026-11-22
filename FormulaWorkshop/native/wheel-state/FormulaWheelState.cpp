// Original optional Formula Workshop instrumentation.
// Compile this file IN Libbulletjme 22.0.3's src/main/native/glue directory,
// using its own Bullet headers/options, into its normal bulletjme library.
// No standalone reader DLL: controller handles must use the host's exact ABI.
// Field meaning was checked against Libbulletjme 22.0.3 and Bullet's vehicle update.
#include <jni.h>
#include "BulletDynamics/Vehicle/btRaycastVehicle.h"

namespace {
constexpr jint wheelCount = 4;
constexpr jint stride = 12;
constexpr jint valueCount = wheelCount * stride;
void reject(JNIEnv* env, const char* text) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, text);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_f1car_scene_NativeWheelState_descriptor(JNIEnv* env, jclass) {
    return env->NewStringUTF(sizeof(btScalar) == 4
        ? "fw-wheel-state-1:lbj-22.0.3:scalar-4"
        : "fw-wheel-state-1:lbj-22.0.3:scalar-8");
}

extern "C" JNIEXPORT void JNICALL
Java_com_f1car_scene_NativeWheelState_read(JNIEnv* env, jclass, jlong handle, jfloatArray output) {
    if (handle == 0 || output == nullptr || env->GetArrayLength(output) != valueCount) {
        reject(env, "Invalid wheel-state request");
        return;
    }
    const auto* vehicle = reinterpret_cast<const btRaycastVehicle*>(handle);
    if (vehicle->getNumWheels() != wheelCount) {
        reject(env, "Wheel-state extension requires the four-wheel FormulaCar layout");
        return;
    }
    jfloat data[valueCount] = {};
    for (jint index = 0; index < wheelCount; ++index) {
        const auto& wheel = vehicle->getWheelInfo(index);
        const auto& ray = wheel.m_raycastInfo;
        const jint at = index * stride;
        data[at] = ray.m_isInContact ? 1.f : 0.f;
        data[at + 1] = static_cast<jfloat>(ray.m_suspensionLength);
        data[at + 2] = static_cast<jfloat>(wheel.getSuspensionRestLength());
        if (!ray.m_isInContact) continue;
        data[at + 3] = static_cast<jfloat>(wheel.m_wheelsSuspensionForce);
        data[at + 4] = static_cast<jfloat>(wheel.m_maxSuspensionForce);
        data[at + 5] = static_cast<jfloat>(wheel.m_skidInfo);
        for (jint axis = 0; axis < 3; ++axis) {
            data[at + 6 + axis] = static_cast<jfloat>(ray.m_contactPointWS[axis]);
            data[at + 9 + axis] = static_cast<jfloat>(ray.m_contactNormalWS[axis]);
        }
    }
    // Pure field reads: no castRay(), updateWheelTransform(), force, impulse or stepping.
    env->SetFloatArrayRegion(output, 0, valueCount, data);
}
