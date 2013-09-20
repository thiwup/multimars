/**
 * Copyright 2013 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.hardwarewallet;

import com.google.common.collect.Sets;
import org.multibit.controller.bitcoin.BitcoinController;
import org.multibit.hardwarewallet.trezor.MockTrezorFactory;
import org.multibit.viewsystem.swing.MultiBitFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.bsol.trezorj.core.Trezor;
import uk.co.bsol.trezorj.core.TrezorEvent;
import uk.co.bsol.trezorj.core.TrezorEventType;
import uk.co.bsol.trezorj.core.TrezorListener;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Class managing hardware wallet devices.
 *
 * This class has responsibility for tracking the hardware wallets being plugged in and removed.
 * It is also responsible for message passing to the hardware wallet(s).
 *
 * It currently understands a single Trezor hardware wallet only.
 *
 * TREZOR support
 * It wraps the Trezor specific events - you can register a HardwareWalletListener to hear
 * when devices are connected and disconnected.
 *
 */
public enum HardwareWalletManager implements TrezorListener {
    INSTANCE;

    private Logger log = LoggerFactory.getLogger(HardwareWalletManager.class);

    private BitcoinController controller;
    private MultiBitFrame mainFrame;

    /**
     * The trezor event queue.
     */
    private BlockingQueue<TrezorEvent> queue;

    /**
     * The hardware wallet that is currently connected (may contain a null Trezor).
     */
    private HardwareWallet hardwareWallet;

    /**
     * Executor service for monitoring incoming trezor events.
     */
    private ExecutorService executorService;

    /**
     * HardwareWalletListeners to the available hardware wallets.
     */
    private Set<HardwareWalletListener> listeners;

    /**
     * Initialise the HardwareWalletManager enum singleton.
     *
     * @param controller
     * @param mainFrame
     */
    public void initialise(BitcoinController controller, MultiBitFrame mainFrame) {
        this.controller = controller;
        this.mainFrame = mainFrame;

        listeners = Sets.newLinkedHashSet();
    }

    /**
     * Add listener to hear hardware wallet events.
     * @param hardwareWalletListener
     */
    public void addListener(HardwareWalletListener hardwareWalletListener) {
        listeners.add(hardwareWalletListener);
    }

    /**
     * Remove hardware wallet event listener.
     * @param hardwareWalletListener
     */
    public void removeListener(HardwareWalletListener hardwareWalletListener) {
        listeners.remove(hardwareWalletListener);
    }

    /**
     * A utility method to create a MockTrezor device - this is a software emulation
     * of a real Trezor. Also starts listening to events.
     *
     * @return a HardwareWallet (This will contain an uninitialised MockTrezor)
     */
    public HardwareWallet createMockTrezor() {
        Trezor trezor = MockTrezorFactory.newMockTrezor();

        // Add this as the listener (sets the event queue)
        trezor.addListener(this);

        hardwareWallet = new DefaultHardwareWallet(trezor);

        // Executor for trezor events.
        executorService = Executors.newSingleThreadExecutor();

        // Set up an executor service to monitor trezor events.
        executorService.submit(new Runnable() {
            @Override
            public void run() {

                BlockingQueue<TrezorEvent> queue = getTrezorEventQueue();

                while (true) {
                    try {
                        TrezorEvent event = queue.take();

                        // Hand over to the event state machine.
                        processEvent(event);

                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        return hardwareWallet;
    }

    /**
     * Process incoming Trezor events and distribute as appropriate to hardwareWalletListeners.
     * @param event
     */
    private void processEvent(TrezorEvent event) {
        if (event == null) {
            return;
        }

        Trezor trezor =  hardwareWallet.getImplementation();

        if (event.eventType().equals(TrezorEventType.DEVICE_CONNECTED)) {
            log.debug("Trezor device '" + trezor.toString() +"' has connected.");
            for (HardwareWalletListener loopListener : listeners) {
                hardwareWallet.setConnected(true);
                loopListener.hasConnected(hardwareWallet);
            }
        } else {
            log.debug("Trezor device '" + trezor.toString() +"' has disconnected.");
            if (event.eventType().equals(TrezorEventType.DEVICE_DISCONNECTED)) {
                for (HardwareWalletListener loopListener : listeners) {
                    loopListener.hasDisconnected(hardwareWallet);
                }
            } else {
                log.debug("Trezor device '" + trezor.toString() +"' has emitted an event '" + event.toString() + "'");
            }
        }
    }

    /**
     * A utility method to destroy the MockTrezor device.
     */
    public void destroyMockTrezor() {
        executorService.shutdown();
        hardwareWallet.getImplementation().removeListener(this);

        hardwareWallet.setInitialised(false);
        hardwareWallet.setConnected(false);
        hardwareWallet = null;
    }

    public HardwareWallet getHardwareWallet() {
        return hardwareWallet;
    }

    // TrezorListener methods.
    @Override
    public BlockingQueue<TrezorEvent> getTrezorEventQueue() {
        return this.queue;
    }

    @Override
    public void setTrezorEventQueue(BlockingQueue<TrezorEvent> trezorEventQueue) {
        this.queue = trezorEventQueue;
    }
}