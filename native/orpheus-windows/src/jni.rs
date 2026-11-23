// SPDX-License-Identifier: MIT

//! The JNI surface: one native method, bound at load time to whatever class and method name Java announces.
//!
//! Nothing here depends on Java class or method names, so the Java side can be renamed and repackaged freely.
//! Before loading, Java stores "<binary class name>\n<method name>" in a system property. `JNI_OnLoad` reads it
//! and registers `call` on that class.

use std::ffi::{CStr, CString, c_void};
use std::ptr;
use std::time::Duration;

use jni_sys::*;

use crate::{ABI, OP_ABI, OP_AWAIT, OP_CLOSE, OP_COMMAND, OP_OPEN, wire};

const HANDSHAKE_PROPERTY: &CStr = c"orpheus-native-bind";
const SIGNATURE: &CStr = c"(IJ[B)[B";

macro_rules! env {
    ($env:expr, $function:ident $(, $argument:expr)*) => {
        ((**$env).$function.unwrap())($env $(, $argument)*)
    };
}

unsafe fn bytes(env: *mut JNIEnv, data: &[u8]) -> jbyteArray {
    unsafe {
        let array = env!(env, NewByteArray, data.len() as jsize);
        if !array.is_null() {
            env!(env, SetByteArrayRegion, array, 0, data.len() as jsize, data.as_ptr() as *const jbyte);
        }
        array
    }
}

unsafe fn read(env: *mut JNIEnv, array: jbyteArray) -> Vec<u8> {
    unsafe {
        if array.is_null() {
            return Vec::new();
        }
        let length = env!(env, GetArrayLength, array);
        let mut data = vec![0u8; length as usize];
        env!(env, GetByteArrayRegion, array, 0, length, data.as_mut_ptr() as *mut jbyte);
        data
    }
}

unsafe extern "system" fn call(
    env: *mut JNIEnv,
    _class: jclass,
    op: jint,
    handle: jlong,
    argument: jbyteArray,
) -> jbyteArray {
    let argument = unsafe { read(env, argument) };
    let outcome = std::panic::catch_unwind(|| -> Option<Vec<u8>> {
        let handle = handle as u64;
        match op {
            OP_ABI => Some(ABI.to_le_bytes().to_vec()),
            OP_OPEN => Some(crate::open().to_le_bytes().to_vec()),
            OP_AWAIT => {
                let millis = u32::from_le_bytes(argument.get(0..4)?.try_into().ok()?);
                crate::await_blob(handle, Duration::from_millis(millis as u64))
            }
            OP_COMMAND => crate::command(handle, wire::command(&argument)?).then(|| vec![1]),
            OP_CLOSE => {
                crate::close(handle);
                Some(Vec::new())
            }
            _ => None,
        }
    });
    match outcome {
        Ok(Some(data)) => unsafe { bytes(env, &data) },
        _ => ptr::null_mut(),
    }
}

unsafe fn bind(env: *mut JNIEnv) -> Option<()> {
    unsafe {
        let system = env!(env, FindClass, c"java/lang/System".as_ptr());
        if system.is_null() {
            return None;
        }
        let get = env!(
            env,
            GetStaticMethodID,
            system,
            c"getProperty".as_ptr(),
            c"(Ljava/lang/String;)Ljava/lang/String;".as_ptr()
        );
        if get.is_null() {
            return None;
        }
        let key = env!(env, NewStringUTF, HANDSHAKE_PROPERTY.as_ptr());
        let value = env!(env, CallStaticObjectMethod, system, get, key);
        if value.is_null() || env!(env, ExceptionCheck) != 0 {
            return None;
        }
        let chars = env!(env, GetStringUTFChars, value, ptr::null_mut());
        if chars.is_null() {
            return None;
        }
        let announced = CStr::from_ptr(chars).to_string_lossy().into_owned();
        env!(env, ReleaseStringUTFChars, value, chars);

        let (class_name, method_name) = announced.split_once('\n')?;
        let class_name = CString::new(class_name.replace('.', "/")).ok()?;
        let method_name = CString::new(method_name).ok()?;
        let class = env!(env, FindClass, class_name.as_ptr());
        if class.is_null() {
            return None;
        }
        let method = JNINativeMethod {
            name: method_name.as_ptr() as *mut _,
            signature: SIGNATURE.as_ptr() as *mut _,
            fnPtr: call as *mut c_void,
        };
        (env!(env, RegisterNatives, class, &method, 1) == JNI_OK).then_some(())
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    unsafe {
        let mut env: *mut JNIEnv = ptr::null_mut();
        if ((**vm).GetEnv.unwrap())(vm, &mut env as *mut _ as *mut *mut c_void, JNI_VERSION_1_8) != JNI_OK {
            return JNI_ERR;
        }
        if bind(env).is_none() {
            if env!(env, ExceptionCheck) != 0 {
                env!(env, ExceptionClear);
            }
            return JNI_ERR;
        }
        JNI_VERSION_1_8
    }
}
