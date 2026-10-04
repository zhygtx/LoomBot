package com.loombot.plugin.storage.store;

import java.util.List;

/**
 * 对象存储抽象。
 *
 * <p>接口只有四个动作：写、读、删、列。这是所有对象存储（本地目录、S3、R2、OSS）都具备的最小集合， 目的就是让"实现换掉"这件事不影响上层：{@code
 * plugin_file.object_key} 存的是对象键， 本地实现把它解释成相对路径，远程实现把它解释成 bucket 内的 key。
 *
 * <p>刻意不做"目录"语义：对象存储没有目录，前缀只是 key 的公共开头。
 */
public interface ObjectStorage {

    /** 后端标识，只用于日志与排查。 */
    String backend();

    /** 写入对象；同名对象直接覆盖。 */
    void put(String objectKey, byte[] data, String contentType);

    /** 读取对象；不存在时抛出 {@link ObjectNotFoundException}。 */
    byte[] get(String objectKey);

    /** 删除对象；返回是否真的删掉了（不存在不算失败）。 */
    boolean delete(String objectKey);

    /** 按前缀列举对象键。 */
    List<String> list(String prefix);

    /** 对象不存在。 */
    class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String message) {
            super(message);
        }
    }
}
