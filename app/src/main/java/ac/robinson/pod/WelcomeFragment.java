package ac.robinson.pod;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class WelcomeFragment extends Fragment {

	private final static String LAYOUT_ID = "layoutId";
	private final static String CHANGE_PIN_MODE = "pinMode";

	private OnCompleteListener mListener;

	public static WelcomeFragment newInstance(int layoutId, boolean changePinMode) {
		WelcomeFragment welcomeFragment = new WelcomeFragment();
		Bundle bundle = new Bundle();
		bundle.putInt(LAYOUT_ID, layoutId);
		bundle.putBoolean(CHANGE_PIN_MODE, changePinMode);
		welcomeFragment.setArguments(bundle);
		return welcomeFragment;
	}

	interface OnCompleteListener {
		void onFragmentInflationComplete(int layoutId);
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		if (context instanceof Activity) {
			Activity activity = (Activity) context;
			try {
				mListener = (OnCompleteListener) activity;
			} catch (final ClassCastException e) {
				throw new ClassCastException(activity.toString() + " must implement OnCompleteListener");
			}
		}
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		int layoutId = getArguments().getInt(LAYOUT_ID, -1);
		View view = inflater.inflate(layoutId, container, false);

		// some hackery to reuse pin change code with slightly different layouts
		boolean pinChangeMode = getArguments().getBoolean(CHANGE_PIN_MODE, false);
		if (pinChangeMode) {
			TextView heading = view.findViewById(R.id.welcome_screen_heading);
			TextView description = view.findViewById(R.id.welcome_screen_description);
			switch (layoutId) {
				case R.layout.fragment_welcome_1:
					if (heading != null) {
						heading.setText(getString(R.string.hint_change_pin_current_pin));
					}
					if (description != null) {
						description.setText(R.string.hint_change_pin_current_pin_reason);
					}
					break;

				case R.layout.fragment_welcome_2:
					if (heading != null) {
						heading.setText(R.string.hint_change_pin_new_pin);
					}
					if (description != null) {
						description.setText(R.string.hint_change_pin_new_pin_suggestion);
					}
					break;

				default:
					break;
			}
		}

		mListener.onFragmentInflationComplete(layoutId);
		return view;
	}
}
