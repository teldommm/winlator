package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.platform.ViewCompositionStrategy;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost;

// Hosted the exact same way ShortcutEditorV2/GameDetailFragment are: replaced into
// MainActivity's own R.id.FLFragmentContainer with addToBackStack. Reuses no Activity
// of its own, so there is no new Window/ActivityRecord to resolve an orientation for -
// this is what previously showed a brief portrait flash as ContainersSettingsActivity /
// OnboardingActivity(component-manager mode), and doesn't as a fragment of MainActivity.
// The catalog/install/remove logic itself lives in ComponentCatalogController, shared
// with OnboardingActivity's own first-run flow so the two don't carry duplicate copies.
public class ComponentManagerFragment extends Fragment implements ComponentCatalogController.Host {
    private static final String ARG_AUTO_INSTALL_TYPE = "auto_install_type";
    private static final String ARG_AUTO_INSTALL_VERSION = "auto_install_version";
    private static final String ARG_AUTO_INSTALL_VERSION_CODE = "auto_install_version_code";

    private final ComponentCatalogController controller = new ComponentCatalogController(this);

    private final ActivityResultLauncher<Intent> localComponentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null) {
                    controller.handleLocalComponentPicked(data.getData());
                }
            }
    );

    private final ActivityResultLauncher<Intent> localDriverLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null) {
                    controller.handleLocalDriverPicked(data.getData());
                }
            }
    );

    public static ComponentManagerFragment newInstance() {
        return new ComponentManagerFragment();
    }

    public static ComponentManagerFragment newInstance(@NonNull String autoInstallType, String autoInstallVersion, int autoInstallVersionCode) {
        ComponentManagerFragment fragment = new ComponentManagerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_AUTO_INSTALL_TYPE, autoInstallType);
        args.putString(ARG_AUTO_INSTALL_VERSION, autoInstallVersion);
        args.putInt(ARG_AUTO_INSTALL_VERSION_CODE, autoInstallVersionCode);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String autoInstallType = args != null ? args.getString(ARG_AUTO_INSTALL_TYPE) : null;
        String autoInstallVersion = args != null ? args.getString(ARG_AUTO_INSTALL_VERSION) : null;
        int autoInstallVersionCode = args != null ? args.getInt(ARG_AUTO_INSTALL_VERSION_CODE, Integer.MIN_VALUE) : Integer.MIN_VALUE;
        controller.initialize(autoInstallType, autoInstallVersion, autoInstallVersionCode);

        FrameLayout root = new FrameLayout(requireContext());
        ComposeView composeView = new ComposeView(requireContext());
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed.INSTANCE);
        root.addView(composeView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        controller.attachComposeController(OnboardingComposeHost.attachToView(
                requireContext(),
                composeView,
                controller.isCoreReady(),
                controller.getCoreProgress(),
                WineRuntimeGuard.isBundledMainInstalled(requireContext()),
                WineRuntimeGuard.isInUse(requireContext(), WineInfo.MAIN_WINE_VERSION.identifier()),
                controller.createCallbacks(new ComponentCatalogController.ExtraCallbacks() {
                    @Override
                    public void onBrowseLocal() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        localComponentLauncher.launch(intent);
                    }

                    @Override
                    public void onBrowseDriver() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        localDriverLauncher.launch(intent);
                    }
                })
        ));

        controller.start();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDetailChrome();
    }

    @Override
    public void onPause() {
        if (!isLandscape() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(false);
        }
        super.onPause();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyDetailChrome() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        activity.setDetailMode(true);
        if (isLandscape()) {
            activity.setBottomNavigationVisible(false);
            activity.setMainToolbarVisible(false);
        }
    }

    @Override
    public void onDestroy() {
        controller.shutdown();
        super.onDestroy();
    }

    // ComponentCatalogController.Host
    @Override
    public Context context() {
        return requireContext();
    }

    @Override
    public AppCompatActivity hostActivity() {
        return (AppCompatActivity) requireActivity();
    }

    @Override
    public boolean isAlive() {
        return isAdded();
    }

    @Override
    public void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(action);
    }

    @Override
    public void close() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }
}
