package io.github.likeelysia.formalagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.UserCommitLock;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

public class OwnerLockProbe {
 static final ObjectMapper mapper=new ObjectMapper();
 static final List<Map<String,Object>> results=new ArrayList<>();
 static final Map<ConversationService,UserCommitLock> locks=new IdentityHashMap<>();
 static ConversationService conversations(Path p){var lock=new UserCommitLock();var c=new ConversationService(new ConversationStore(mapper,p),lock);locks.put(c,lock);c.ensureDefault();return c;}
 static ReentrantLock actualLock(ConversationService c)throws Exception{var f=UserCommitLock.class.getDeclaredField("lock");f.setAccessible(true);return (ReentrantLock)f.get(locks.get(c));}
 static Conversation snapshot(ConversationService c,String id){return c.record(id,"remember this synthetic learning fact","synthetic answer",List.of());}
 static List<MemoryCandidate> candidates(){return List.of(new MemoryCandidate("SYNTHETIC_OWNER_FACT",1));}
 static LongTermMemory memory(MemoryStore s,ConversationService c,MemoryExtractor extractor,Path p,java.util.concurrent.Executor ex,MemoryReconciler reconciler,MemoryConflictStore cf){
  var cfg=new AppConfig.Memory.LongTerm(true,p.toString(),1,0,5,.9,10,1,100);
  var m=new LongTermMemory(s,extractor,t->new float[]{1,0},c,cfg,ex,reconciler,cf);m.setCommitLock(locks.get(c));return m;
 }
 static void result(String name,boolean passed,Map<String,Object> detail){results.add(Map.of("test",name,"passed",passed,"detail",detail));}
 static class PausingStore extends MemoryStore {
  final CountDownLatch checked=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicBoolean committed=new AtomicBoolean();
  PausingStore(Path p){super(mapper,p,"mock");}
  @Override public BatchCommit updateBatch(String scope,long generation,String batch,UnaryOperator<List<MemoryEntry>> mutator){
   var result=super.updateBatch(scope,generation,batch,entries->{var changed=mutator.apply(entries);checked.countDown();await(release);return changed;});committed.set(true);return result;
  }
 }
 static class PausingConflicts extends MemoryConflictStore {
  final CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);
  PausingConflicts(Path p){super(mapper,p);}
  @Override public void addAll(String scope,List<String> text,String conversation){ready.countDown();await(release);super.addAll(scope,text,conversation);}
 }
 static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
 static boolean awaitQueued(ReentrantLock lock,AtomicReference<Thread> t,Future<?> action){long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<until){if(t.get()!=null&&lock.hasQueuedThread(t.get()))return true;if(action.isDone())return false;Thread.yield();}return false;}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);
  for(String action:List.of("normal","delete-before","scope-before","clear-before","shared")){
   Path p=root.resolve(action);var c=conversations(p.resolve("conversations"));String id=c.create("source").id(),other=c.create("other").id();if(action.equals("shared"))c.shareMemory(id,other);var snap=snapshot(c,id);
   var s=new MemoryStore(mapper,p.resolve("memory"),"mock");LongTermMemory[] ref=new LongTermMemory[1];
   ref[0]=memory(s,c,t->{if(action.equals("delete-before"))c.delete(id);if(action.equals("scope-before"))c.shareMemory(id,other);if(action.equals("clear-before"))ref[0].forgetAll(id);return MemoryExtractor.Result.ok(candidates());},p.resolve("memory"),Runnable::run,(a,b)->Map.of(),null);
   ref[0].maybeExtract(snap);int own=s.list(id).size(),shared=s.list(other).size();boolean passed=action.equals("normal")?own==1:action.equals("shared")?shared==1&&own==0:own==0&&shared==0;
   result(action,passed,Map.of("source_entries",own,"other_entries",shared));
  }
  for(String action:List.of("delete","scope")){
   Path p=root.resolve("after-check-"+action);var c=conversations(p.resolve("conversations"));String id=c.create("source").id(),other=c.create("other").id();var snap=snapshot(c,id);var s=new PausingStore(p.resolve("memory"));
   var cf=new MemoryConflictStore(mapper,p.resolve("memory/conflicts.json"));var worker=Executors.newSingleThreadExecutor();var mutation=Executors.newSingleThreadExecutor();
   try{
    var m=memory(s,c,t->MemoryExtractor.Result.ok(candidates()),p.resolve("memory"),worker,(a,b)->Map.of(0,new MemoryReconciler.Decision(MemoryReconciler.CORE_CONFLICT,null,null)),cf);m.maybeExtract(snap);await(s.checked);
    AtomicReference<Thread> thread=new AtomicReference<>();AtomicBoolean committedWhenMutationReturned=new AtomicBoolean();
    Future<?> f=mutation.submit(()->{thread.set(Thread.currentThread());if(action.equals("delete"))c.delete(id);else c.shareMemory(id,other);committedWhenMutationReturned.set(s.committed.get());});
    boolean queued=awaitQueued(actualLock(c),thread,f);s.release.countDown();f.get(5,TimeUnit.SECONDS);worker.shutdown();if(!worker.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("worker timeout");
    result(action+"_after_check_serialized",queued&&committedWhenMutationReturned.get(),Map.of("mutation_waited_for_commit",queued,"commit_finished_before_mutation_return",committedWhenMutationReturned.get(),"entries",s.list(id).size(),"conflicts",cf.count()));
   }finally{s.release.countDown();worker.shutdownNow();mutation.shutdownNow();}
  }
  {
   Path p=root.resolve("clear-before-conflict-write");var c=conversations(p.resolve("conversations"));String id=c.create("source").id();var snap=snapshot(c,id);var s=new MemoryStore(mapper,p.resolve("memory"),"mock");var cf=new PausingConflicts(p.resolve("memory/conflicts.json"));var worker=Executors.newSingleThreadExecutor();var mutation=Executors.newSingleThreadExecutor();
   try{
    var m=memory(s,c,t->MemoryExtractor.Result.ok(candidates()),p.resolve("memory"),worker,(a,b)->Map.of(0,new MemoryReconciler.Decision(MemoryReconciler.CORE_CONFLICT,null,null)),cf);m.maybeExtract(snap);await(cf.ready);
    AtomicReference<Thread> thread=new AtomicReference<>();Future<?> f=mutation.submit(()->{thread.set(Thread.currentThread());m.forgetAll(id);});
    boolean queued=awaitQueued(actualLock(c),thread,f);boolean clearCompletedBeforeConflict=f.isDone();cf.release.countDown();f.get(5,TimeUnit.SECONDS);worker.shutdown();if(!worker.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("worker timeout");
    result("clear_coordinated_with_conflict_commit",queued||cf.count()==0,Map.of("clear_waited_for_owner_commit",queued,"clear_completed_before_conflict_write",clearCompletedBeforeConflict,"memory_entries",s.list(id).size(),"conflicts_after_clear",cf.count()));
   }finally{cf.release.countDown();worker.shutdownNow();mutation.shutdownNow();}
  }
  {
   Path p=root.resolve("slow-calls-outside-lock");var c=conversations(p.resolve("conversations"));String id=c.create("source").id();var snap=snapshot(c,id);var s=new MemoryStore(mapper,p.resolve("memory"),"mock");var lock=actualLock(c);AtomicBoolean embeddingHeld=new AtomicBoolean(),reconcileHeld=new AtomicBoolean();
   var cfg=new AppConfig.Memory.LongTerm(true,p.resolve("memory").toString(),1,0,5,.9,10,1,100);
   var m=new LongTermMemory(s,t->MemoryExtractor.Result.ok(candidates()),t->{embeddingHeld.set(lock.isHeldByCurrentThread());return new float[]{1,0};},c,cfg,Runnable::run,(a,b)->{reconcileHeld.set(lock.isHeldByCurrentThread());return Map.of();},null);m.setCommitLock(locks.get(c));m.maybeExtract(snap);
   result("embedding_and_reconciliation_outside_owner_lock",!embeddingHeld.get()&&!reconcileHeld.get(),Map.of("embedding_holds_owner_lock",embeddingHeld.get(),"reconcile_holds_owner_lock",reconcileHeld.get()));
  }
  mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("owner-lock-results.json").toFile(),results);System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
 }
}
