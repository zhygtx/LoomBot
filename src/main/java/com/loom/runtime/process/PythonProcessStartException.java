package com.loom.runtime.process;

/** Python 进程启动失败。 */
public class PythonProcessStartException extends RuntimeException {

    public PythonProcessStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
