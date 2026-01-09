package com.udacity.webcrawler.profiler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class ProfilingMethodInterceptor implements InvocationHandler {

  private final Object delegate;
  private final Clock clock;
  private final ProfilingState state;

  ProfilingMethodInterceptor(Object delegate, Clock clock, ProfilingState state) {
    this.delegate = Objects.requireNonNull(delegate);
    this.clock = Objects.requireNonNull(clock);
    this.state = Objects.requireNonNull(state);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

    // Never profile Object methods
    if (method.getDeclaringClass() == Object.class) {
      try {
        return method.invoke(delegate, args);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    if (!method.isAnnotationPresent(Profiled.class)) {
      try {
        return method.invoke(delegate, args);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    Instant start = clock.instant();
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException e) {
      throw e.getTargetException(); // exact exception
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e); // REQUIRED FIX
    } finally {
      Instant end = clock.instant();
      state.record(delegate.getClass(), method, Duration.between(start, end));
    }
  }
}
