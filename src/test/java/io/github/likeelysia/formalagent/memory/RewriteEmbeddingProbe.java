package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.UserCommitLock;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

public class RewriteEmbeddingProbe {
 public static void main(String[] args) throws Exception {
  var mapper = new ObjectMapper(); var p = Path.of(args[0]); Files.createDirectories(p);
  var owner = new UserCommitLock();
  var lf = UserCommitLock.class.getDeclaredField("lock"); lf.setAccessible(true);
  var ownerLock = (ReentrantLock) lf.get(owner);
  var c = new ConversationService(new ConversationStore(mapper,p.resolve("conversations")),owner);
  c.ensureDefault(); var id = c.create("synthetic").id();
  var snap = c.record(id,"synthetic question","synthetic answer",List.of());
  var s = new MemoryStore(mapper,p.resolve("memory"),"mock");
  var sf = MemoryStore.class.getDeclaredField("lock"); sf.setAccessible(true); var storageLock=sf.get(s);
  s.update(id, entries -> List.of(MemoryEntry.at("old",id,"OLD_SYNTHETIC_FACT",id,1000,new float[]{1,0})));
  var calls = new ArrayList<Map<String,Object>>();
  var cfg = new AppConfig.Memory.LongTerm(true,p.resolve("memory").toString(),1,0,5,.9,10,1,100);
  var m = new LongTermMemory(s, t -> MemoryExtractor.Result.ok(List.of(new MemoryCandidate("CANDIDATE_FACT",1))),
   text -> { calls.add(Map.of("text",text,"owner_lock_held",ownerLock.isHeldByCurrentThread(),"storage_lock_held",Thread.holdsLock(storageLock))); return text.equals("REWRITTEN_FACT")?new float[]{0,1}:new float[]{1,0}; },
   c,cfg,Runnable::run,(a,b)->Map.of(0,new MemoryReconciler.Decision(MemoryReconciler.UPDATE,"old","REWRITTEN_FACT")),null);
  m.setCommitLock(owner); m.maybeExtract(snap);
  var active=s.list(id).stream().filter(MemoryEntry::active).toList();
  boolean saved=active.size()==1 && active.get(0).text().equals("REWRITTEN_FACT") && Arrays.equals(active.get(0).embedding(),new float[]{0,1});
  boolean called=calls.stream().anyMatch(x->x.get("text").equals("REWRITTEN_FACT"));
  boolean outside=calls.stream().allMatch(x->!(Boolean)x.get("owner_lock_held") && !(Boolean)x.get("storage_lock_held"));
  var result=Map.of("test","rewritten_text_embedding_outside_both_locks","passed",saved&&called&&outside,"rewritten_text_and_vector_saved",saved,"embedding_calls",calls);
  mapper.writerWithDefaultPrettyPrinter().writeValue(p.resolve("rewrite-results.json").toFile(),result);
  System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
 }
}
