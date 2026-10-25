/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.agever.render;

import android.util.Log;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.Entity;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.TransformManager;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.FilamentInstance;

import org.petalos.agever.core.ControlSnapshot;
import org.petalos.agever.core.Expression;

import java.util.HashMap;
import java.util.Map;

// named rig nodes when available, otherwise move the whole bloody model
final class AvatarRig {

    private static final String TAG = "AgeVerRig";
    private static final String NODE_ROOT = "Avatar";
    private static final String NODE_HEAD = "Head";
    private static final String NODE_BODY = "Body";
    private static final String NODE_EYE_L = "EyeL";
    private static final String NODE_EYE_R = "EyeR";
    private static final String NODE_MOUTH = "Mouth";

    // Labelled face bones on the old-man scan. All of them bind with an
    // identity joint matrix, so a plain translation moves exactly that region.
    private static final String BONE_EYE_R = "Bone.001";
    private static final String BONE_EYE_L = "Bone.002";
    private static final String BONE_JAW = "JAW_Open";
    private static final String BONE_UPPER_LIP = "MOUTH_Upper_Lip";
    private static final String BONE_LOWER_LIP = "MOUTH_Lower_Lip";
    private static final String BONE_CHEEK_L = "Bone.005";
    private static final String BONE_CHEEK_R = "Bone.006";

    private static final float HEAD_MAX_YAW = 0.9f;
    private static final float HEAD_MAX_PITCH = 0.6f;

    /** Raw-model framing: target height/depth of the recentred model in world units. */
    private static final float TARGET_HEIGHT = 0.9f;
    private static final float TARGET_Y = 1.45f;

    private final TransformManager mTransforms;
    private final RenderableManager mRenderables;
    private final Animator mAnimator;

    private final int mRoot;
    private final int mHead;
    private final int mBody;
    private final int mEyeL;
    private final int mEyeR;
    private final int mMouth;

    private final float[] mRootBase;
    private final float[] mHeadBase;
    private final float[] mBodyBase;
    private final float[] mEyeLBase;
    private final float[] mEyeRBase;
    private final float[] mMouthBase;

    private final int mBoneEyeR;
    private final int mBoneEyeL;
    private final int mBoneJaw;
    private final int mBoneUpperLip;
    private final int mBoneLowerLip;
    private final int mBoneCheekL;
    private final int mBoneCheekR;

    private final float[] mBoneEyeRBase;
    private final float[] mBoneEyeLBase;
    private final float[] mBoneJawBase;
    private final float[] mBoneUpperLipBase;
    private final float[] mBoneLowerLipBase;
    private final float[] mBoneCheekLBase;
    private final float[] mBoneCheekRBase;

    private final boolean mHasFaceBones;

    private final boolean mRawModel;
    private final float[] mRecenter = TransformUtils.identity();

    private final Map<String, Integer> mHeadMorphs = new HashMap<>();
    private int mHeadMorphCount;

    private final float[] mScratch = new float[16];
    private final float[] mPose = new float[16];
    private boolean mBoneErrorLogged;
    private boolean mBoneAppliedLogged;

    AvatarRig(Engine engine, FilamentAsset asset) {
        mTransforms = engine.getTransformManager();
        mRenderables = engine.getRenderableManager();
        FilamentInstance instance = asset.getInstance();
        mAnimator = instance != null ? instance.getAnimator() : null;

        mHead = find(asset, NODE_HEAD);
        mBody = find(asset, NODE_BODY);
        mEyeL = find(asset, NODE_EYE_L);
        mEyeR = find(asset, NODE_EYE_R);
        mMouth = find(asset, NODE_MOUTH);

        int root = find(asset, NODE_ROOT);
        mRawModel = root == 0;
        if (mRawModel) {
            int r = asset.getRoot();
            root = (r != 0 && mTransforms.hasComponent(r)) ? r : find(asset, "Sketchfab_model");
        }
        mRoot = root;

        mRootBase = base(mRoot);
        mHeadBase = base(mHead);
        mBodyBase = base(mBody);
        mEyeLBase = base(mEyeL);
        mEyeRBase = base(mEyeR);
        mMouthBase = base(mMouth);

        mBoneEyeR = find(asset, BONE_EYE_R);
        mBoneEyeL = find(asset, BONE_EYE_L);
        mBoneJaw = find(asset, BONE_JAW);
        mBoneUpperLip = find(asset, BONE_UPPER_LIP);
        mBoneLowerLip = find(asset, BONE_LOWER_LIP);
        mBoneCheekL = find(asset, BONE_CHEEK_L);
        mBoneCheekR = find(asset, BONE_CHEEK_R);

        mBoneEyeRBase = base(mBoneEyeR);
        mBoneEyeLBase = base(mBoneEyeL);
        mBoneJawBase = base(mBoneJaw);
        mBoneUpperLipBase = base(mBoneUpperLip);
        mBoneLowerLipBase = base(mBoneLowerLip);
        mBoneCheekLBase = base(mBoneCheekL);
        mBoneCheekRBase = base(mBoneCheekR);

        mHasFaceBones = mBoneUpperLip != 0 && mBoneLowerLip != 0 && mBoneJaw != 0
                && mBoneEyeR != 0 && mBoneEyeL != 0
                && mBoneCheekL != 0 && mBoneCheekR != 0;

        if (mHead != 0) {
            String[] names = asset.getMorphTargetNames(mHead);
            if (names != null) {
                mHeadMorphCount = names.length;
                for (int i = 0; i < names.length; i++) {
                    if (names[i] != null) {
                        mHeadMorphs.put(names[i], i);
                    }
                }
            }
        }

        if (mRawModel) {
            buildRecenter(asset);
        }

        Log.d(TAG, "Avatar rig loaded");
    }

    /** Centres a node-less model in front of the camera and scales it to fit. */
    private void buildRecenter(FilamentAsset asset) {
        Box box = asset.getBoundingBox();
        if (box == null) {
            return;
        }
        float[] center = box.getCenter();
        float[] half = box.getHalfExtent();
        float height = Math.max(1e-3f, half[1] * 2f);
        float s = TARGET_HEIGHT / height;
        // mRecenter = T(0, TARGET_Y, 0) * S(s) * T(-center)
        TransformUtils.multiply(mRecenter,
                TransformUtils.translate(0f, TARGET_Y, 0f),
                TransformUtils.scale(s, s, s));
        TransformUtils.multiply(mRecenter, mRecenter,
                TransformUtils.translate(-center[0], -center[1], -center[2]));
    }

    boolean hasHead() {
        return mHead != 0;
    }

    int expressionMask() {
        if (mHasFaceBones) return (1 << Expression.values().length) - 1;
        if (mRawModel) return 0;
        int mask = 0;
        if (mMouth != 0 || mHeadMorphs.containsKey("jawOpen")) mask |= 1 << Expression.MOUTH_OPEN.ordinal();
        if (mMouth != 0 || mHeadMorphs.containsKey("mouthSmile")
                || mHeadMorphs.containsKey("mouthSmileLeft") || mHeadMorphs.containsKey("mouthSmileRight")) {
            mask |= 1 << Expression.SMILE.ordinal();
        }
        if (mHeadMorphs.containsKey("mouthFrownLeft") || mHeadMorphs.containsKey("mouthFrownRight")) {
            mask |= 1 << Expression.FROWN.ordinal();
        }
        if (mHeadMorphs.containsKey("browInnerUp") || mHeadMorphs.containsKey("browOuterUpLeft")
                || mHeadMorphs.containsKey("browOuterUpRight")) mask |= 1 << Expression.BROW_UP.ordinal();
        if (mHeadMorphs.containsKey("browDownLeft") || mHeadMorphs.containsKey("browDownRight")) {
            mask |= 1 << Expression.BROW_DOWN.ordinal();
        }
        if ((mask & (1 << Expression.MOUTH_OPEN.ordinal())) != 0
                && (mask & (1 << Expression.BROW_UP.ordinal())) != 0) mask |= 1 << Expression.SURPRISE.ordinal();
        return mask == 0 ? 0 : mask | (1 << Expression.NEUTRAL.ordinal());
    }

    private int find(FilamentAsset asset, String name) {
        int e = asset.getFirstEntityByName(name);
        return e == Entity.NULL ? 0 : e;
    }

    private float[] base(int entity) {
        if (entity == 0 || !mTransforms.hasComponent(entity)) {
            return TransformUtils.identity();
        }
        return mTransforms.getTransform(mTransforms.getInstance(entity), new float[16]);
    }

    // pose it on the render thread, idle motion uses monotonic time
    void apply(ControlSnapshot s, boolean idle, double timeSec, boolean mirror) {
        float headYaw = s.headYaw;
        float headPitch = s.headPitch;
        float headRoll = s.headRoll;
        float bodyYaw = s.bodyYaw;
        float bodyPitch = s.bodyPitch;
        float blink = s.eyeBlinkLeft;
        float blinkRight = s.eyeBlinkRight;

        if (idle) {
            float sway = (float) Math.sin(timeSec * 0.5) * 0.06f;
            float bob = (float) Math.sin(timeSec * 0.9) * 0.045f;
            headYaw += sway;
            headPitch += bob;
            bodyYaw += sway * 0.4f;
            float autonomousBlink = blinkWave(timeSec);
            blink = Math.max(blink, autonomousBlink);
            blinkRight = Math.max(blinkRight, autonomousBlink);
        }

        headYaw = clamp(headYaw, -HEAD_MAX_YAW, HEAD_MAX_YAW);
        headPitch = clamp(headPitch, -HEAD_MAX_PITCH, HEAD_MAX_PITCH);

        applyFaceBones(s, blink, blinkRight);

        if (mRawModel) {
            applyRawModel(s, headYaw, headPitch, headRoll, mirror);
            updateBoneMatrices();
            return;
        }

        if (mHead != 0) {
            TransformUtils.multiply(mPose, mHeadBase,
                    TransformUtils.euler(headPitch, headYaw, headRoll));
            mTransforms.setTransform(mTransforms.getInstance(mHead), mPose);
        }
        if (mBody != 0) {
            TransformUtils.multiply(mPose, mBodyBase,
                    TransformUtils.euler(bodyPitch, bodyYaw, 0f));
            mTransforms.setTransform(mTransforms.getInstance(mBody), mPose);
        }

        applyMorphs(s, blink, blinkRight);

        if (mMouth != 0) {
            float open = clamp(s.mouthOpen + s.mouthSmile * 0.25f, 0f, 1f);
            float smile = clamp(s.mouthSmile, 0f, 1f);
            TransformUtils.multiply(mScratch, mMouthBase,
                    TransformUtils.translate(0f, -0.012f * open, 0f));
            TransformUtils.multiply(mPose, mScratch,
                    TransformUtils.scale(1f + 0.25f * smile, 1f + 0.9f * open, 1f));
            mTransforms.setTransform(mTransforms.getInstance(mMouth), mPose);
        }
        if (mEyeL != 0) {
            mTransforms.setTransform(mTransforms.getInstance(mEyeL), blinkTransform(mEyeLBase, blink));
        }
        if (mEyeR != 0) {
            mTransforms.setTransform(mTransforms.getInstance(mEyeR), blinkTransform(mEyeRBase, blinkRight));
        }

        if (mRoot != 0) {
            float sx = mirror ? -1f : 1f;
            TransformUtils.multiply(mScratch, mRootBase,
                    TransformUtils.translate(s.offsetX, s.offsetY, s.offsetZ));
            TransformUtils.multiply(mPose, mScratch, TransformUtils.scale(sx, 1f, 1f));
            mTransforms.setTransform(mTransforms.getInstance(mRoot), mPose);
        }
        updateBoneMatrices();
    }

    /** Copies the current joint transforms into the skinned mesh for this frame. */
    private void updateBoneMatrices() {
        if (mAnimator != null) {
            mAnimator.updateBoneMatrices();
        }
    }

    /** Recentre + tilt the whole model as one piece. */
    private void applyRawModel(ControlSnapshot s, float yaw, float pitch, float roll,
            boolean mirror) {
        if (mRoot == 0) {
            return;
        }
        // Tilt around the recentred centre (target y).
        float[] rot = TransformUtils.euler(pitch, yaw, roll);
        float[] aboutCenter = TransformUtils.multiply(
                TransformUtils.translate(0f, TARGET_Y, 0f),
                TransformUtils.multiply(rot, TransformUtils.translate(0f, -TARGET_Y, 0f)));
        // placement stick moves the model across/height and dollies it nearer/further.
        float[] placement = TransformUtils.translate(s.offsetX, s.offsetY, s.offsetZ);
        float[] world = TransformUtils.multiply(placement,
                TransformUtils.multiply(aboutCenter, mRecenter));
        if (mirror) {
            world = TransformUtils.multiply(TransformUtils.scale(-1f, 1f, 1f), world);
        }
        TransformUtils.multiply(mPose, world, mRootBase);
        mTransforms.setTransform(mTransforms.getInstance(mRoot), mPose);
    }

    private void applyMorphs(ControlSnapshot s, float blink, float blinkRight) {
        if (mHead == 0 || mHeadMorphCount == 0 || !mRenderables.hasComponent(mHead)) {
            return;
        }
        float[] weights = new float[mHeadMorphCount];
        put(weights, "jawOpen", s.mouthOpen);
        put(weights, "mouthSmile", s.mouthSmile);
        put(weights, "mouthSmileLeft", s.mouthSmile);
        put(weights, "mouthSmileRight", s.mouthSmile);
        put(weights, "mouthFrownLeft", s.mouthFrown);
        put(weights, "mouthFrownRight", s.mouthFrown);
        put(weights, "eyeBlinkLeft", blink);
        put(weights, "eyeBlinkRight", blinkRight);
        put(weights, "browInnerUp", s.browUp);
        put(weights, "browOuterUpLeft", s.browUp);
        put(weights, "browOuterUpRight", s.browUp);
        put(weights, "browDownLeft", s.browDown);
        put(weights, "browDownRight", s.browDown);
        mRenderables.setMorphWeights(mRenderables.getInstance(mHead), weights, 0);
    }

    // these bones sit at the origin; rotating them flings the mesh around
    private void applyFaceBones(ControlSnapshot s, float blink, float blinkRight) {
        if (!mHasFaceBones) {
            return;
        }
        float open = clamp(s.mouthOpen, 0f, 1f);
        float smile = clamp(s.mouthSmile, 0f, 1f);
        float frown = clamp(s.mouthFrown, 0f, 1f);
        float browUp = clamp(s.browUp, 0f, 1f);
        float browDown = clamp(s.browDown, 0f, 1f);
        float wide = clamp(s.eyeWide, 0f, 1f);

        if (!mBoneAppliedLogged
                && (open + smile + frown + browUp + browDown + wide > 0f)) {
            mBoneAppliedLogged = true;
            Log.i(TAG, "expr open=" + open + " smile=" + smile + " frown=" + frown
                    + " up=" + browUp + " down=" + browDown + " wide=" + wide);
        }

        float eyeRY = 0.14f * browUp - 0.12f * browDown + 0.08f * wide
                - 0.16f * clamp(blink, 0f, 1f);
        float eyeLY = 0.14f * browUp - 0.12f * browDown + 0.08f * wide
                - 0.16f * clamp(blinkRight, 0f, 1f);

        setBone(mBoneEyeR, mBoneEyeRBase, 0f, eyeRY, 0f);
        setBone(mBoneEyeL, mBoneEyeLBase, 0f, eyeLY, 0f);
        setBone(mBoneJaw, mBoneJawBase, 0f, -0.28f * open, 0f);
        setBone(mBoneUpperLip, mBoneUpperLipBase, 0f,
                0.10f * open + 0.04f * smile - 0.05f * frown, 0f);
        setBone(mBoneLowerLip, mBoneLowerLipBase, 0f,
                -0.14f * open + 0.06f * smile, 0.02f * open);
        setBone(mBoneCheekL, mBoneCheekLBase,
                0.16f * smile - 0.10f * frown, 0.13f * smile - 0.14f * frown, 0f);
        setBone(mBoneCheekR, mBoneCheekRBase,
                -0.16f * smile + 0.10f * frown, 0.13f * smile - 0.14f * frown, 0f);
    }

    private void setBone(int entity, float[] boneBase, float tx, float ty, float tz) {
        if (entity == 0) {
            return;
        }
        // boneBase * IBM == identity for this rig, so D * boneBase lands the
        // bone's weighted verts exactly at +D.
        try {
            TransformUtils.multiply(mPose, TransformUtils.translate(tx, ty, tz), boneBase);
            mTransforms.setTransform(mTransforms.getInstance(entity), mPose);
        } catch (RuntimeException e) {
            if (!mBoneErrorLogged) {
                mBoneErrorLogged = true;
                Log.e(TAG, "setBone failed for " + entity, e);
            }
        }
    }

    private void put(float[] weights, String name, float value) {
        Integer index = mHeadMorphs.get(name);
        if (index != null && index < weights.length) {
            weights[index] = value;
        }
    }

    private float[] blinkTransform(float[] base, float blink) {
        float sy = 1f - 0.85f * clamp(blink, 0f, 1f);
        TransformUtils.multiply(mScratch, base, TransformUtils.scale(1f, sy, 1f));
        return mScratch.clone();
    }

    /** A short blink every ~4.2 s (first at ~2 s). */
    private static float blinkWave(double t) {
        double phase = (t - 2.0) % 4.2;
        if (phase < 0) {
            phase += 4.2;
        }
        if (phase > 0.16) {
            return 0f;
        }
        double x = phase / 0.16;
        return (float) Math.sin(x * Math.PI);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
