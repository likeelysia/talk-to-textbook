package io.github.likeelysia.formalagent.knowledge;

import java.util.List;
import java.util.Map;

/** 知识库:存知识点 + 按查询检索。实现可换(JSON 文件 → 数据库 → 向量库)。 */
public interface KnowledgeStore {

    /** 批量加入(同 id 覆盖)。 */
    void addAll(List<KnowledgeItem> items);

    /** 取出全部知识点。 */
    List<KnowledgeItem> all();

    /** 检索最相关的 topK 条(向量检索;embedding 服务不可用时降级为关键词匹配)。 */
    List<KnowledgeItem> search(String query, int topK);

    /**
     * 所有来源(按首次出现顺序),典型的来源 = 一本教材的文件名。
     *
     * <p>给"按来源删除/重灌"和列表展示用 —— 用户输入的名字要先拿来核对。
     */
    List<String> sources();

    /**
     * 按来源删除(精确匹配全名),返回删除的条数。
     *
     * <p>{@code source} 为空/空白时返回 0 —— 宁可什么都没干,也不能因为一个手滑的
     * 空参数把整个知识库清空。
     */
    int removeBySource(String source);

    /**
     * 原子替换某个来源的全部条目:先删掉它的旧条目,再写入新的,返回写入条数。
     *
     * <p>用途 = <b>重灌同一本书</b>:教材改版/知识点改名后,若只靠"同 id 覆盖",
     * 新版里删掉的那些知识点会永远留在库里(新旧混杂) —— 这个方法让这本"书"整份换掉。
     */
    int replaceSource(String source, List<KnowledgeItem> items);

    /** 各来源的条目数(保序,给列表展示用)。 */
    Map<String, Integer> countsBySource();
}
