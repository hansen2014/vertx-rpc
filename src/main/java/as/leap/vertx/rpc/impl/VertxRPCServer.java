package as.leap.vertx.rpc.impl;


import as.leap.vertx.rpc.RPCServer;
import as.leap.vertx.rpc.VertxRPCException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;
import rx.Observable;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 *
 */
public class VertxRPCServer extends RPCBase implements RPCServer {
  private static final Logger log = LoggerFactory.getLogger(VertxRPCServer.class);

  private final LocalMap<String, SharedWrapper> serviceMapping;
  private final MessageConsumer<byte[]> consumer;
  private RPCServerOptions options;
  private Vertx vertx;

  public VertxRPCServer(RPCServerOptions options) {
    super(options.getWireProtocol());
    vertx = options.getVertx();
    checkBusAddress(options.getBusAddress());
    this.options = options;
    if (options.getServiceMapping().size() == 0)
      throw new VertxRPCException("please add service implementation to RPCServerOptions.");
    this.serviceMapping = options.getServiceMapping();
    this.consumer = options.getVertx().eventBus().consumer(options.getBusAddress());
    this.consumer.setMaxBufferedMessages(options.getMaxBufferedMessages());
    registryService();
  }

  private void registryService() {
    consumer.handler(message -> {
      try {
        RPCRequest request = asObject(message.body(), RPCRequest.class);
        VertxRPCServer.this.call(request, message);
      } catch (Exception e) {
        replyFail(e, message);
      }
    });
  }

  private void call(RPCRequest request, Message<byte[]> message) {
    try {
      Object service = serviceMapping.get(request.getServiceName()).getValue();
      Class<?>[] argClasses = {};
      Object[] args = {};
      //args
      if (request.getArgs() != null && request.getArgs().size() > 0) {
        List<Object> argList = request.getArgs();
        int argCount = argList.size() >>> 1;
        args = new Object[argCount];
        argClasses = new Class[argCount];

        for (int i = 0; i < argList.size(); i += 2) {
          int index = i >>> 1;
          String argClassName = (String) argList.get(i);
          byte[] argBytes = (byte[]) argList.get(i + 1);

          Class<?> argClass = Class.forName(argClassName);
          Object arg = asObject(argBytes, argClass);
          //check type for get real class and real value
          if (arg instanceof WrapperType) {
            argClasses[index] = ((WrapperType) arg).getClazz();
            args[index] = ((WrapperType) arg).getValue();
          } else {
            argClasses[index] = arg.getClass();
            args[index] = arg;
          }
        }
      }

      CallbackType callbackType = CallbackType.valueOf(message.headers().get(CALLBACK_TYPE));
      final Object[] finalArgs = args;
      final Class<?>[] finalArgClasses = argClasses;
      //hook
      vertx.executeBlocking(future -> {
        options.getRpcHook().beforeHandler(request.getServiceName(), request.getMethodName(), finalArgs, message.headers().remove(CALLBACK_TYPE));
        future.complete();
      }, false, event -> executeInvoke(callbackType, request, message, service, finalArgClasses, finalArgs));
    } catch (Exception e) {
      replyFail(e, message);
    }
  }

  private <T> void executeInvoke(CallbackType callbackType, RPCRequest request, Message<byte[]> message, Object service, Class<?>[] argClasses, Object[] args) {
    try {
      switch (callbackType) {
        case REACTIVE:
          Observable<?> observable = (Observable) service.getClass().getMethod(request.getMethodName(), argClasses).invoke(service, args);
          observable.subscribe(result -> replySuccess(result, message), ex -> replyFail(ex, message));
          break;
        case ASYNC_HANDLER:
          argClasses = Arrays.copyOf(argClasses, argClasses.length + 1);
          argClasses[argClasses.length - 1] = Handler.class;
          args = Arrays.copyOf(args, args.length + 1);
          args[args.length - 1] = (Handler<AsyncResult<T>>) event -> {
            if (event.succeeded()) replySuccess(event.result(), message);
            else replyFail(event.cause(), message);
          };
          service.getClass().getMethod(request.getMethodName(), argClasses).invoke(service, args);
          break;
        case COMPLETABLE_FUTURE:
          CompletableFuture<?> future = (CompletableFuture) service.getClass().getMethod(request.getMethodName(), argClasses).invoke(service, args);
          future.whenComplete((result, ex) -> {
            if (ex != null) replyFail(ex, message);
            else replySuccess(result, message);
          });
          break;
        case SYNC:
          final Class<?>[] finalArgClasses = argClasses;
          final Object[] finalArgs = args;
          //wrap code with worker thread
          vertx.executeBlocking(event -> {
            try {
              Object result = service.getClass().getMethod(request.getMethodName(), finalArgClasses).invoke(service, finalArgs);
              event.complete(result);
            } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
              if (e instanceof InvocationTargetException)
                event.fail(((InvocationTargetException) e).getTargetException());
              else event.fail(e);
            }
          }, false, event -> {
            if (event.succeeded()) replySuccess(event.result(), message);
            else replyFail(event.cause(), message);
          });
          break;
      }
    } catch (Exception e) {
      replyFail(e, message);
    }
  }

  private <T> void replySuccess(T result, Message<byte[]> message) {
    //hook
    vertx.executeBlocking(future -> {
      options.getRpcHook().afterHandler(result, message.headers());
      future.complete();
    }, false, event -> {
      String resultClassName;
      byte[] resultBytes = {};
      try {
        if (Optional.ofNullable(result).isPresent()) {
          Class<?> resultClass = result.getClass();
          resultClassName = isWrapType(resultClass) ? WrapperType.class.getName() : resultClass.getName();
          resultBytes = asBytes(result);
        } else {
          // result is null, so we have wrap it.
          resultClassName = WrapperType.class.getName();
        }
        RPCResponse response = new RPCResponse(resultClassName, resultBytes);
        byte[] responseBytes = asBytes(response);
        message.reply(responseBytes);
      } catch (Exception e) {
        replyFail(e, message);
      }
    });
  }

  private void replyFail(Throwable ex, Message<byte[]> message) {
    //hook
    vertx.executeBlocking(future -> {
          log.error(ex.getMessage(), ex);
          options.getRpcHook().afterHandler(ex, message.headers());
          future.complete();
        }, false,
        event -> {
          Throwable realEx = ex.getCause() != null && !ex.getCause().equals(ex) ? ex.getCause() : ex;
          message.fail(500, realEx.getClass().getName() + "|" + (realEx.getMessage() == null ? realEx.getStackTrace()[0].getMethodName() : realEx.getMessage()));
        });
  }

  @Override
  public void shutdown(Handler<AsyncResult<Void>> handler) {
    if (Optional.ofNullable(handler).isPresent()) {
      consumer.unregister(handler);
    } else {
      consumer.unregister();
    }
  }
}
