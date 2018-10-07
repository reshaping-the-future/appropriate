package ac.robinson.pod.views;

import android.content.Context;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.MotionEvent;

// see: http://stackoverflow.com/a/34076755/1993220
public class CustomSwipeViewPager extends ViewPager {

	public enum SwipeDirection {
		ALL, NONE, LEFT, RIGHT
	}

	private float mInitialXValue;
	private SwipeDirection mDirection;

	public CustomSwipeViewPager(Context context, AttributeSet attrs) {
		super(context, attrs);
		this.mDirection = SwipeDirection.ALL;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return this.IsSwipeAllowed(event) && super.onTouchEvent(event);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		return this.IsSwipeAllowed(event) && super.onInterceptTouchEvent(event);
	}

	private boolean IsSwipeAllowed(MotionEvent event) {
		if (mDirection == SwipeDirection.ALL) {
			return true;
		}

		if (mDirection == SwipeDirection.NONE) {
			return false; //disable all swipes
		}

		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			mInitialXValue = event.getX();
			return true;
		}

		if (event.getAction() == MotionEvent.ACTION_MOVE) {
			try {
				float xDiff = event.getX() - mInitialXValue;
				if (xDiff > 0 && mDirection == SwipeDirection.RIGHT) {
					return false; // swipe from left to right detected
				} else if (xDiff < 0 && mDirection == SwipeDirection.LEFT) {
					return false; // swipe from right to left detected
				}
			} catch (Exception ignored) {
			}
		}
		return true;
	}

	public void setAllowedSwipeDirection(SwipeDirection direction) {
		mDirection = direction;
	}
}
