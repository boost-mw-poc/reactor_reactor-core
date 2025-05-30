/*
 * Copyright (c) 2022-2025 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import io.micrometer.context.ContextSnapshot;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable.ConditionalSubscriber;
import reactor.core.observability.SignalListener;
import reactor.core.observability.SignalListenerFactory;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

/**
 * A generic per-Subscription side effect {@link Flux} that notifies a {@link SignalListener} of most events.
 *
 * @author Simon Baslé
 */
final class FluxTapRestoringThreadLocals<T, STATE> extends FluxOperator<T, T> {

	final SignalListenerFactory<T, STATE> tapFactory;
	final STATE                           commonTapState;

	FluxTapRestoringThreadLocals(Flux<? extends T> source, SignalListenerFactory<T, STATE> tapFactory) {
		super(source);
		this.tapFactory = tapFactory;
		this.commonTapState = tapFactory.initializePublisherState(source);
	}

	@Override
	@SuppressWarnings("try")
	public void subscribe(CoreSubscriber<? super T> actual) {
		//if the SignalListener cannot be created, all we can do is error the subscriber.
		//after it is created, in case doFirst fails we can additionally try to invoke doFinally.
		//note that if the later handler also fails, then that exception is thrown.
		SignalListener<T> signalListener;
		try {
			//TODO replace currentContext() with contextView() when available
			signalListener = tapFactory.createListener(source, actual.currentContext().readOnly(), commonTapState);
		}
		catch (Throwable generatorError) {
			Operators.error(actual, generatorError);
			return;
		}

		try {
			signalListener.doFirst();
		}
		catch (Throwable listenerError) {
			signalListener.handleListenerError(listenerError);
			Operators.error(actual, listenerError);
			return;
		}

		// invoked AFTER doFirst
		Context alteredContext;
		try {
			alteredContext = signalListener.addToContext(actual.currentContext());
		}
		catch (Throwable e) {
			IllegalStateException listenerError = new IllegalStateException(
					"Unable to augment tap Context at subscription via addToContext", e);
			signalListener.handleListenerError(listenerError);
			Operators.error(actual, listenerError);
			return;
		}

		try (ContextSnapshot.Scope ignored = ContextPropagation.setThreadLocals(alteredContext)) {
			source.subscribe(new TapSubscriber<>(actual, signalListener, alteredContext));
		}
	}

	@Nullable
	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
		if (key == InternalProducerAttr.INSTANCE) return true;
		return super.scanUnsafe(key);
	}

	//TODO support onErrorContinue around listener errors
	static class TapSubscriber<T> implements ConditionalSubscriber<T>, InnerOperator<T, T> {

		final CoreSubscriber<? super T> actual;
		final ConditionalSubscriber<? super T> actualConditional;
		final Context context;
		final SignalListener<T> listener;

		boolean done;
		Subscription s;

		TapSubscriber(CoreSubscriber<? super T> actual, SignalListener<T> signalListener, Context ctx) {
			this.actual = actual;
			this.listener = signalListener;
			this.context = ctx;
			if (actual instanceof ConditionalSubscriber) {
				this.actualConditional = (ConditionalSubscriber<? super T>) actual;
			} else {
				this.actualConditional = null;
			}
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Override
		public Context currentContext() {
			return this.context;
		}

		@Override
		@Nullable
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.RUN_STYLE) return Attr.RunStyle.SYNC;
			if (key == InternalProducerAttr.INSTANCE) return true;
			return InnerOperator.super.scanUnsafe(key);
		}

		/**
		 * Cancel the prepared subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)}
		 * and then terminate the downstream directly with same error (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method before the subscription was set
		 * @param toCancel      the {@link Subscription} that was prepared but not sent downstream
		 */
		@SuppressWarnings("try")
		protected void handleListenerErrorPreSubscription(Throwable listenerError, Subscription toCancel) {
			toCancel.cancel();
			listener.handleListenerError(listenerError);
			try (ContextSnapshot.Scope ignored =
					     ContextPropagation.setThreadLocals(actual.currentContext())) {
				Operators.error(actual, listenerError);
			}
		}

		/**
		 * Cancel the active subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)}
		 * and then terminate the downstream directly with same error (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method
		 */
		@SuppressWarnings("try")
		protected void handleListenerErrorAndTerminate(Throwable listenerError) {
			s.cancel();
			listener.handleListenerError(listenerError);
			try (ContextSnapshot.Scope ignored =
					     ContextPropagation.setThreadLocals(actual.currentContext())) {
				actual.onError(listenerError); //TODO hooks ?
			}
		}

		/**
		 * Cancel the active subscription, pass the listener error to {@link SignalListener#handleListenerError(Throwable)},
		 * combine it with the original error and then terminate the downstream directly this combined exception
		 * (without invoking any other handler).
		 *
		 * @param listenerError the exception thrown from a handler method
		 * @param originalError the exception that was about to occur when handler was invoked
		 */
		@SuppressWarnings("try")
		protected void handleListenerErrorMultipleAndTerminate(Throwable listenerError, Throwable originalError) {
			s.cancel();
			listener.handleListenerError(listenerError);
			RuntimeException multiple = Exceptions.multiple(listenerError, originalError);
			try (ContextSnapshot.Scope ignored =
					     ContextPropagation.setThreadLocals(actual.currentContext())) {
				actual.onError(multiple); //TODO hooks ?
			}
		}

		/**
		 * After the downstream is considered terminated (or cancelled), pass the listener error to
		 * {@link SignalListener#handleListenerError(Throwable)} then drop that error.
		 *
		 * @param listenerError the exception thrown from a handler method happening after sequence termination
		 */
		protected void handleListenerErrorPostTermination(Throwable listenerError) {
			listener.handleListenerError(listenerError);
			Operators.onErrorDropped(listenerError, actual.currentContext());
		}

		@Override
		@SuppressWarnings("try")
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				try {
					listener.doOnSubscription();
				} catch (Throwable observerError) {
					handleListenerErrorPreSubscription(observerError, s);
					return;
				}
				try (ContextSnapshot.Scope ignored =
							 ContextPropagation.setThreadLocals(actual.currentContext())) {
					actual.onSubscribe(this);
				}
			}
		}

		@Override
		@SuppressWarnings("try")
		public void onNext(T t) {
			if (done) {
				try {
					listener.doOnMalformedOnNext(t);
				} catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				} finally {
					Operators.onNextDropped(t, currentContext());
				}
				return;
			}
			try {
				listener.doOnNext(t);
			} catch (Throwable observerError) {
				handleListenerErrorAndTerminate(observerError);
				return;
			}
			try (ContextSnapshot.Scope ignored =
						 ContextPropagation.setThreadLocals(actual.currentContext())) {
				actual.onNext(t);
			}
		}

		@Override
		@SuppressWarnings("try")
		public boolean tryOnNext(T t) {
			try (ContextSnapshot.Scope ignored =
						 ContextPropagation.setThreadLocals(actual.currentContext())) {
				if (actualConditional != null) {
					if (!actualConditional.tryOnNext(t)) {
						return false;
					}
				} else {
					actual.onNext(t);
				}
			}
			try {
				listener.doOnNext(t);
			} catch (Throwable listenerError) {
				handleListenerErrorAndTerminate(listenerError);
			}
			return true;
		}

		@Override
		@SuppressWarnings("try")
		public void onError(Throwable t) {
			if (done) {
				try {
					listener.doOnMalformedOnError(t);
				} catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				} finally {
					Operators.onErrorDropped(t, currentContext());
				}
				return;
			}
			done = true;

			try {
				listener.doOnError(t);
			} catch (Throwable observerError) {
				//any error in the hooks interrupts other hooks, including doFinally
				handleListenerErrorMultipleAndTerminate(observerError, t);
				return;
			}

			try (ContextSnapshot.Scope ignored =
					     ContextPropagation.setThreadLocals(actual.currentContext())) {
				actual.onError(t); //RS: onError MUST terminate normally and not throw
			}

			try {
				listener.doAfterError(t);
				listener.doFinally(SignalType.ON_ERROR);
			} catch (Throwable observerError) {
				handleListenerErrorPostTermination(observerError);
			}
		}

		@Override
		@SuppressWarnings("try")
		public void onComplete() {
			if (done) {
				try {
					listener.doOnMalformedOnComplete();
				} catch (Throwable observerError) {
					handleListenerErrorPostTermination(observerError);
				}
				return;
			}
			done = true;

			try {
				listener.doOnComplete();
			} catch (Throwable observerError) {
				handleListenerErrorAndTerminate(observerError);
				return;
			}

			try (ContextSnapshot.Scope ignored =
						 ContextPropagation.setThreadLocals(actual.currentContext())) {
				actual.onComplete(); //RS: onComplete MUST terminate normally and not throw
			}

			try {
				listener.doAfterComplete();
				listener.doFinally(SignalType.ON_COMPLETE);
			} catch (Throwable observerError) {
				handleListenerErrorPostTermination(observerError);
			}
		}

		@Override
		@SuppressWarnings("try")
		public void request(long n) {
			try (ContextSnapshot.Scope ignored =
						 ContextPropagation.setThreadLocals(this.context)) {
				if (Operators.validate(n)) {
					try {
						listener.doOnRequest(n);
					} catch (Throwable observerError) {
						handleListenerErrorAndTerminate(observerError);
						return;
					}
					s.request(n);
				}
			}
		}

		@Override
		@SuppressWarnings("try")
		public void cancel() {
			try (ContextSnapshot.Scope ignored =
						 ContextPropagation.setThreadLocals(this.context)) {
				try {
					listener.doOnCancel();
				} catch (Throwable observerError) {
					handleListenerErrorAndTerminate(observerError);
					return;
				}

				try {
					s.cancel();
				} finally {
					try {
						listener.doFinally(SignalType.CANCEL);
					} catch (Throwable observerError) {
						handleListenerErrorAndTerminate(observerError); //redundant s.cancel
					}
				}
			}
		}
	}
}
