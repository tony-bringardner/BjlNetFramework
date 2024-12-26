/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.net.framework.client;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;

public class CertificateValidotorDialog extends JDialog implements DynamicTrustManager.CertificateValidator {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private final JPanel contentPanel = new JPanel();
	private JTextPane textPane;
	private ManageAs response;
	
	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		try {
			CertificateValidotorDialog dialog = new CertificateValidotorDialog();
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setVisible(true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public ManageAs validate(java.security.cert.X509Certificate cert) {
		response = ManageAs.REJECT;
		
		
		StringBuilder buf = new StringBuilder("The certificate is invalid.  Please review and validate.\n");
		
		buf.append(cert.getSubjectDN().getName()+"\n");
		buf.append("Good from "+cert.getNotBefore()+" to "+cert.getNotAfter()+"\n\nWould you like to accept this certificate?");
		textPane.setText(buf.toString());
		
		setModal(true);
		setVisible(true);
		
		return this.response;
	}
	
	
	/**
	 * Create the dialog.
	 */
	public CertificateValidotorDialog() {
		setBounds(100, 100, 450, 300);
		getContentPane().setLayout(new BorderLayout());
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
		getContentPane().add(contentPanel, BorderLayout.CENTER);
		contentPanel.setLayout(new BorderLayout(0, 0));
		{
			textPane = new JTextPane();
			contentPanel.add(textPane, BorderLayout.CENTER);
		}
		{
			JPanel buttonPane = new JPanel();
			buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
			getContentPane().add(buttonPane, BorderLayout.SOUTH);
			{
				JButton rejectButton = new JButton("Reject");
				rejectButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent arg0) {
						response = ManageAs.REJECT;
						dispose();
					}
				});
				buttonPane.add(rejectButton);
			}
			{
				JButton acceptButton = new JButton("Accept");
				acceptButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent arg0) {
						response = ManageAs.ACCEPT_ONCE;
						dispose();
					}
				});
				buttonPane.add(acceptButton);
				getRootPane().setDefaultButton(acceptButton);
			}
			{
				JButton alwaysButton = new JButton("Accept Always");
				alwaysButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent arg0) {
						response = ManageAs.ACCEPT_ALWAYS;
						dispose();
					}
				});
				alwaysButton.setActionCommand("Always");
				buttonPane.add(alwaysButton);
			}
		}
	}

	

}
